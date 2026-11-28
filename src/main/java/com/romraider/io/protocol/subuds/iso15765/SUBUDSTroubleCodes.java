/*
 * RomRaider Open-Source Tuning, Logging and Reflashing
 * Copyright (C) 2006-2026 RomRaider.com
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package com.romraider.io.protocol.subuds.iso15765;

import static com.romraider.io.protocol.subuds.iso15765.SUBUDSProtocol.RESPONSE_NON_DATA_BYTES;
import static com.romraider.util.HexUtil.asHex;
import static com.romraider.util.HexUtil.hexToInt;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import com.romraider.logger.ecu.comms.query.EcuQuery;
import com.romraider.logger.ecu.definition.EcuData;
import com.romraider.logger.ecu.definition.EcuDtcConvertorImpl;
import com.romraider.logger.ecu.definition.Module;
import com.romraider.logger.ecu.exception.InvalidResponseException;

/**
 * Trouble codes over UDS: one ReadDTCInformation request, 19 02 FF (report every
 * code whose status matches mask FF), answered with 59 02, the status
 * availability mask, then a 3-byte code and a status byte for each code.
 * <p>
 * In the definition each &lt;dtcode&gt; under the SUBUDS protocol carries the
 * 3-byte code number as both tmpaddr and memaddr (P0335 is 0x000335). Each
 * entry gets the two bytes RomRaider's DTC convertor expects: its bit set in the
 * first byte if the code is pending (status bit 2) and in the second if it is
 * confirmed, i.e. stored (status bit 3).
 */
public final class SUBUDSTroubleCodes {
    public static final byte READ_DTC_COMMAND = (byte) 0x19;
    public static final byte READ_DTC_RESPONSE = (byte) 0x59;
    public static final byte REPORT_BY_STATUS_MASK = (byte) 0x02;
    public static final byte ALL_STATUS_BITS = (byte) 0xFF;
    static final int STATUS_PENDING = 0x04;
    static final int STATUS_CONFIRMED = 0x08;

    private SUBUDSTroubleCodes() {
        throw new UnsupportedOperationException();
    }

    /** True if every query is a trouble code (the logger's Read Codes), not a parameter. */
    public static boolean isTroubleCodeQuery(Collection<EcuQuery> queries) {
        if (queries == null || queries.isEmpty()) return false;
        for (EcuQuery query : queries) {
            if (!(query.getLoggerData().getSelectedConvertor() instanceof EcuDtcConvertorImpl)) {
                return false;
            }
        }
        return true;
    }

    public static byte[] constructRequest(Module module) {
        final byte[] tester = module.getTester();
        final byte[] request = new byte[tester.length + 3];
        System.arraycopy(tester, 0, request, 0, tester.length);
        request[tester.length] = READ_DTC_COMMAND;
        request[tester.length + 1] = REPORT_BY_STATUS_MASK;
        request[tester.length + 2] = ALL_STATUS_BITS;
        return request;
    }

    /** Code number to status byte, from a 59 02 response (CAN ID first). */
    public static Map<Integer, Integer> parseResponse(Module module, byte[] response) {
        SUBUDSResponseProcessor.validateResponse(module, response);
        final int base = RESPONSE_NON_DATA_BYTES;
        if (response.length < base + 3
                || response[base] != READ_DTC_RESPONSE
                || response[base + 1] != REPORT_BY_STATUS_MASK
                || (response.length - base - 3) % 4 != 0) {
            throw new InvalidResponseException("Unexpected trouble code response: " + asHex(response));
        }
        final Map<Integer, Integer> codes = new HashMap<Integer, Integer>();
        for (int i = base + 3; i < response.length; i += 4) {
            final int code = ((response[i] & 0xFF) << 16)
                    | ((response[i + 1] & 0xFF) << 8) | (response[i + 2] & 0xFF);
            codes.put(code, response[i + 3] & 0xFF);
        }
        return codes;
    }

    /** Give each trouble-code query its pending / stored bits from the parsed response. */
    public static void apply(Collection<EcuQuery> queries, Map<Integer, Integer> codes) {
        for (EcuQuery query : queries) {
            final int bit = ((EcuData) query.getLoggerData()).getAddress().getBit();
            final Integer status = codes.get(hexToInt(query.getAddresses()[0]));
            final byte[] data = new byte[2];
            if (status != null) {
                if ((status & STATUS_PENDING) != 0) data[0] = (byte) (1 << bit);
                if ((status & STATUS_CONFIRMED) != 0) data[1] = (byte) (1 << bit);
            }
            query.setResponse(data);
        }
    }
}
