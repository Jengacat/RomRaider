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

import static com.romraider.io.protocol.subuds.iso15765.SUBUDSProtocol.NRC;
import static com.romraider.io.protocol.subuds.iso15765.SUBUDSProtocol.NRC_OUT_OF_RANGE;
import static com.romraider.io.protocol.subuds.iso15765.SUBUDSProtocol.READ_DID_COMMAND;
import static com.romraider.io.protocol.subuds.iso15765.SUBUDSProtocol.READ_DID_RESPONSE;
import static com.romraider.io.protocol.subuds.iso15765.SUBUDSProtocol.RESPONSE_NON_DATA_BYTES;
import static com.romraider.util.HexUtil.asHex;
import static com.romraider.util.ParamChecker.checkNotNull;
import static com.romraider.util.ParamChecker.checkNotNullOrEmpty;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.romraider.io.protocol.Protocol;
import com.romraider.io.protocol.ProtocolSUBUDS;
import com.romraider.logger.ecu.comms.io.protocol.LoggerProtocolSUBUDS;
import com.romraider.logger.ecu.comms.manager.PollingState;
import com.romraider.logger.ecu.comms.query.EcuInit;
import com.romraider.logger.ecu.comms.query.EcuInitCallback;
import com.romraider.logger.ecu.comms.query.EcuQuery;
import com.romraider.logger.ecu.definition.Module;
import com.romraider.logger.ecu.exception.InvalidResponseException;
import com.romraider.logger.ecu.exception.UnsupportedProtocolException;

public final class SUBUDSLoggerProtocol implements LoggerProtocolSUBUDS {
    private final ProtocolSUBUDS protocol = new SUBUDSProtocol();

    @Override
    public byte[] constructEcuInitRequest(Module module) {
        return protocol.constructEcuInitRequest(module);
    }

    @Override
    public byte[] constructStartDiagRequest(Module module) {
        return protocol.constructStartDiagRequest(module);
    }

    @Override
    public byte[] constructEcuStopRequest(Module module) {
        return protocol.constructEcuStopRequest(module);
    }

    @Override
    public byte[] constructTesterPresentRequest(Module module) {
        return protocol.constructTesterPresentRequest(module);
    }

    @Override
    public byte[] constructClearAddressRequest(Module module) {
        return protocol.constructClearAddressRequest(module);
    }

    @Override
    public byte[] constructEcuResetRequest(Module module, int resetCode) {
        return protocol.constructEcuResetRequest(module, resetCode);
    }

    @Override
    public SUBUDSAddressPlan buildAddressPlan(Collection<EcuQuery> queries) {
        return SUBUDSAddressPlan.build(filterDuplicates(queries));
    }

    @Override
    public byte[][] constructLoadAddressRequests(Module module, SUBUDSAddressPlan plan) {
        checkNotNull(plan, "plan");
        final int[] dids = plan.getDids();
        final byte[][] requests = new byte[dids.length][];
        for (int i = 0; i < dids.length; i++) {
            final List<long[]> ranges = plan.getRanges(i);
            final byte[][] addresses = new byte[ranges.size()][];
            final int[] lengths = new int[ranges.size()];
            for (int k = 0; k < ranges.size(); k++) {
                final long start = ranges.get(k)[0];
                addresses[k] = new byte[]{
                        (byte) (start >> 24), (byte) (start >> 16),
                        (byte) (start >> 8), (byte) start};
                lengths[k] = (int) ranges.get(k)[1];
            }
            requests[i] = protocol.constructLoadAddressRequest(module, dids[i], addresses, lengths);
        }
        return requests;
    }

    @Override
    public void validateLoadAddressResponse(byte[] response, int did) {
        protocol.validateLoadAddressResponse(response, did);
    }

    @Override
    public byte[] constructReadAddressRequest(Module module, SUBUDSAddressPlan plan) {
        checkNotNull(plan, "plan");
        return protocol.constructReadAddressRequest(module, plan.getDids());
    }

    @Override
    public byte[] constructReadAddressResponse(SUBUDSAddressPlan plan) {
        checkNotNull(plan, "plan");
        return new byte[RESPONSE_NON_DATA_BYTES + plan.getReplyLength()];
    }

    @Override
    public boolean isDefinitionLost(byte[] response) {
        return response != null
                && response.length > RESPONSE_NON_DATA_BYTES + 2
                && response[RESPONSE_NON_DATA_BYTES] == NRC
                && response[RESPONSE_NON_DATA_BYTES + 1] == READ_DID_COMMAND
                && response[RESPONSE_NON_DATA_BYTES + 2] == NRC_OUT_OF_RANGE;
    }

    @Override
    public void processReadAddressResponses(
            Collection<EcuQuery> queries, byte[] response, SUBUDSAddressPlan plan) {

        checkNotNullOrEmpty(queries, "queries");
        checkNotNullOrEmpty(response, "response");
        checkNotNull(plan, "plan");
        SUBUDSResponseProcessor.validateResponse(
                ((SUBUDSProtocol) protocol).getModule(), response);
        final int base = RESPONSE_NON_DATA_BYTES;
        if (response[base] != READ_DID_RESPONSE
                || response.length < base + plan.getReplyLength()) {
            throw new InvalidResponseException(
                    "Unexpected read response: " + asHex(response));
        }
        // each identifier echo must be where the plan expects it; the ECU skips
        // an identifier that is no longer defined, which shifts everything after it
        final int[] dids = plan.getDids();
        int offset = 1;
        for (int i = 0; i < dids.length; i++) {
            if ((response[base + offset] & 0xFF) != ((dids[i] >> 8) & 0xFF)
                    || (response[base + offset + 1] & 0xFF) != (dids[i] & 0xFF)) {
                throw new InvalidResponseException(String.format(
                        "Read response is missing identifier %04X: %s", dids[i], asHex(response)));
            }
            offset += 2;
            for (long[] r : plan.getRanges(i)) {
                offset += (int) r[1];
            }
        }
        for (EcuQuery query : queries) {
            final int[] positions = plan.getPositions(query);
            final byte[] data = new byte[positions.length];
            for (int k = 0; k < positions.length; k++) {
                data[k] = response[base + positions[k]];
            }
            query.setResponse(data);
        }
    }

    // LoggerProtocol methods for callers that do not know about plans

    @Override
    public byte[] constructReadAddressRequest(Module module, Collection<EcuQuery> queries) {
        return constructReadAddressRequest(module, buildAddressPlan(queries));
    }

    @Override
    public byte[] constructReadAddressResponse(
            Collection<EcuQuery> queries, PollingState pollState) {
        return constructReadAddressResponse(buildAddressPlan(queries));
    }

    @Override
    public void processReadAddressResponses(
            Collection<EcuQuery> queries, byte[] response, PollingState pollState) {
        processReadAddressResponses(queries, response, buildAddressPlan(queries));
    }

    @Override
    public byte[] preprocessResponse(byte[] request, byte[] response, PollingState pollState) {
        return SUBUDSResponseProcessor.filterRequestFromResponse(request, response, pollState);
    }

    @Override
    public void processEcuInitResponse(EcuInitCallback callback, byte[] response) {
        checkNotNull(callback, "callback");
        checkNotNullOrEmpty(response, "response");
        final EcuInit ecuInit = protocol.parseEcuInitResponse(response);
        callback.callback(ecuInit);
    }

    @Override
    public void processEcuResetResponse(byte[] response) {
        protocol.checkValidEcuResetResponse(response);
    }

    @Override
    public Protocol getProtocol() {
        return protocol;
    }

    @Override
    public byte[] constructWriteAddressRequest(Module module, byte[] writeAddress, byte value) {
        throw new UnsupportedProtocolException(
                "Write address command is not supported by the SUBUDS protocol");
    }

    @Override
    public void processWriteResponse(byte[] data, byte[] processedResponse) {
    }

    @Override
    public Collection<EcuQuery> filterDuplicates(Collection<EcuQuery> queries) {
        final Collection<EcuQuery> filteredQueries = new ArrayList<EcuQuery>();
        for (EcuQuery query : queries) {
            if (!filteredQueries.contains(query)) {
                filteredQueries.add(query);
            }
        }
        return filteredQueries;
    }
}
