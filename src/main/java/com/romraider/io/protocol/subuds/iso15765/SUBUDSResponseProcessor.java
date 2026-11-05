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

import static com.romraider.io.protocol.subuds.iso15765.SUBUDSProtocol.ECU_INIT_RESPONSE;
import static com.romraider.io.protocol.subuds.iso15765.SUBUDSProtocol.LOAD_ADDRESS_RESPONSE;
import static com.romraider.io.protocol.subuds.iso15765.SUBUDSProtocol.NRC;
import static com.romraider.io.protocol.subuds.iso15765.SUBUDSProtocol.READ_DID_RESPONSE;
import static com.romraider.io.protocol.subuds.iso15765.SUBUDSProtocol.RESPONSE_NON_DATA_BYTES;
import static com.romraider.io.protocol.subuds.iso15765.SUBUDSProtocol.SESSION_RESPONSE;
import static com.romraider.io.protocol.subuds.iso15765.SUBUDSProtocol.TESTER_PRESENT_RESPONSE;
import static com.romraider.util.ByteUtil.asUnsignedInt;
import static com.romraider.util.HexUtil.asHex;
import static com.romraider.util.ParamChecker.checkNotNullOrEmpty;

import com.romraider.logger.ecu.comms.manager.PollingState;
import com.romraider.logger.ecu.definition.Module;
import com.romraider.logger.ecu.exception.InvalidResponseException;

public final class SUBUDSResponseProcessor {

    private SUBUDSResponseProcessor() {
        throw new UnsupportedOperationException();
    }

    public static byte[] filterRequestFromResponse(
            byte[] request, byte[] response, PollingState pollState) {
        checkNotNullOrEmpty(response, "response");
        // With J2534 loopback off only the response is present
        return response;
    }

    public static void validateResponse(Module module, byte[] response) {
        assertTrue(response.length > RESPONSE_NON_DATA_BYTES, "Invalid response length");
        assertEquals(module.getAddress(), response, "Invalid " + module.getName() + " id");
        if (response[RESPONSE_NON_DATA_BYTES] == NRC) {
            throw new InvalidResponseException(String.format(
                    "%s refused %s: %s (NRC %s)",
                    module.getName(),
                    response.length > 5 ? asHex(response[5]) : "?",
                    response.length > 6 ? describeNrc(response[6]) : "no code",
                    response.length > 6 ? asHex(response[6]) : "?"));
        }
        assertOneOf(new byte[]{ECU_INIT_RESPONSE, SESSION_RESPONSE, LOAD_ADDRESS_RESPONSE,
                READ_DID_RESPONSE, TESTER_PRESENT_RESPONSE},
                response[RESPONSE_NON_DATA_BYTES], "Invalid response code");
    }

    /** Data after the CAN ID and the response code. */
    public static byte[] extractResponseData(Module module, byte[] response) {
        checkNotNullOrEmpty(response, "response");
        validateResponse(module, response);
        final int skip = RESPONSE_NON_DATA_BYTES + 1;
        final byte[] data = new byte[response.length - skip];
        System.arraycopy(response, skip, data, 0, data.length);
        return data;
    }

    static String describeNrc(byte code) {
        switch (code) {
            case 0x10: return "general reject";
            case 0x11: return "service not supported";
            case 0x12: return "sub-function not supported";
            case 0x13: return "incorrect message length or format";
            case 0x22: return "conditions not correct";
            case 0x24: return "request sequence error";
            case 0x31: return "request out of range";
            case 0x33: return "security access denied";
            case 0x7E: return "sub-function not supported in active session";
            case 0x7F: return "service not supported in active session";
            default: return "unknown";
        }
    }

    private static void assertTrue(boolean condition, String msg) {
        if (!condition) {
            throw new InvalidResponseException(msg);
        }
    }

    private static void assertEquals(byte[] expected, byte[] actual, String msg) {
        final byte[] idBytes = new byte[4];
        System.arraycopy(actual, 0, idBytes, 0, 4);
        final int idExpected = asUnsignedInt(expected);
        final int idActual = asUnsignedInt(idBytes);
        if (idActual != idExpected) {
            throw new InvalidResponseException(msg + ". Expected: " + asHex(expected) +
                    ". Actual: " + asHex(idBytes) + ".");
        }
    }

    private static void assertOneOf(byte[] validOptions, byte actual, String msg) {
        for (byte option : validOptions) {
            if (option == actual) {
                return;
            }
        }
        final StringBuilder builder = new StringBuilder();
        for (int i = 0; i < validOptions.length; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(asHex(validOptions[i]));
        }
        throw new InvalidResponseException(String.format(
                "%s. Expected one of [%s]. Actual: %s.", msg, builder.toString(), asHex(actual)));
    }
}
