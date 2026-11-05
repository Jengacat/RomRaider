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

import static com.romraider.util.HexUtil.asHex;
import static com.romraider.util.ParamChecker.checkNotNull;
import static com.romraider.util.ParamChecker.checkNotNullOrEmpty;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import com.romraider.io.connection.ConnectionProperties;
import com.romraider.io.protocol.ProtocolSUBUDS;
import com.romraider.logger.ecu.comms.manager.PollingState;
import com.romraider.logger.ecu.comms.query.EcuInit;
import com.romraider.logger.ecu.comms.query.SSMEcuInit;
import com.romraider.logger.ecu.definition.Module;
import com.romraider.logger.ecu.exception.InvalidResponseException;
import com.romraider.logger.ecu.exception.UnsupportedProtocolException;

/**
 * Request building and reply checks for Subaru UDS logging over CAN.
 * <pre>
 * ECU ID           000007E0 AA                           -> 000007E8 EA ... (SSM init, also answered in this session)
 * start            000007E0 10 03                        -> 000007E8 50 03
 * clear all        000007E0 2C 03                        -> 000007E8 6C 03
 * define           000007E0 2C 02 F3xx 14 {addr4 len1}.. -> 000007E8 6C 02 F3xx
 * read             000007E0 22 F3xx [F3yy ...]           -> 000007E8 62 F3xx data [F3yy data ...]
 * tester present   000007E0 3E 00                        -> 000007E8 7E 00
 * stop             000007E0 10 01                        -> 000007E8 50 01
 * </pre>
 */
public final class SUBUDSProtocol implements ProtocolSUBUDS {
    public static final byte ECU_INIT_COMMAND = (byte) 0xAA;
    public static final byte ECU_INIT_RESPONSE = (byte) 0xEA;
    public static final byte SESSION_COMMAND = (byte) 0x10;
    public static final byte SESSION_RESPONSE = (byte) 0x50;
    public static final byte SESSION_DEFAULT = (byte) 0x01;
    public static final byte SESSION_EXTENDED = (byte) 0x03;
    public static final byte LOAD_ADDRESS_COMMAND = (byte) 0x2C;
    public static final byte LOAD_ADDRESS_RESPONSE = (byte) 0x6C;
    public static final byte DEFINE_BY_MEMORY_ADDRESS = (byte) 0x02;
    public static final byte CLEAR_DEFINITIONS = (byte) 0x03;
    public static final byte ADDRESS_AND_LENGTH_FORMAT = (byte) 0x14;
    public static final byte READ_DID_COMMAND = (byte) 0x22;
    public static final byte READ_DID_RESPONSE = (byte) 0x62;
    public static final byte TESTER_PRESENT_COMMAND = (byte) 0x3E;
    public static final byte TESTER_PRESENT_RESPONSE = (byte) 0x7E;
    public static final byte NRC = (byte) 0x7F;
    public static final byte NRC_OUT_OF_RANGE = (byte) 0x31;
    public static final int RESPONSE_NON_DATA_BYTES = 4;     // CAN ID
    private final ByteArrayOutputStream bb = new ByteArrayOutputStream(255);
    private Module module;

    @Override
    public byte[] constructEcuInitRequest(Module module) {
        checkNotNull(module, "module");
        this.module = module;
        return buildRequest(ECU_INIT_COMMAND);
    }

    @Override
    public byte[] constructStartDiagRequest(Module module) {
        checkNotNull(module, "module");
        this.module = module;
        return buildRequest(SESSION_COMMAND, SESSION_EXTENDED);
    }

    @Override
    public byte[] constructEcuStopRequest(Module module) {
        checkNotNull(module, "module");
        this.module = module;
        return buildRequest(SESSION_COMMAND, SESSION_DEFAULT);
    }

    @Override
    public byte[] constructTesterPresentRequest(Module module) {
        checkNotNull(module, "module");
        this.module = module;
        return buildRequest(TESTER_PRESENT_COMMAND, (byte) 0x00);
    }

    @Override
    public byte[] constructClearAddressRequest(Module module) {
        checkNotNull(module, "module");
        this.module = module;
        return buildRequest(LOAD_ADDRESS_COMMAND, CLEAR_DEFINITIONS);
    }

    @Override
    public byte[] constructLoadAddressRequest(
            Module module, int did, byte[][] addresses, int[] lengths) {
        checkNotNull(module, "module");
        checkNotNullOrEmpty(addresses, "addresses");
        this.module = module;
        bb.reset();
        try {
            bb.write(module.getTester());
            bb.write(LOAD_ADDRESS_COMMAND);
            bb.write(DEFINE_BY_MEMORY_ADDRESS);
            bb.write(did >> 8);
            bb.write(did);
            bb.write(ADDRESS_AND_LENGTH_FORMAT);
            for (int i = 0; i < addresses.length; i++) {
                bb.write(addresses[i]);
                bb.write(lengths[i]);
            }
        }
        catch (IOException e) {
            e.printStackTrace();
        }
        return bb.toByteArray();
    }

    @Override
    public void validateLoadAddressResponse(byte[] response, int did) {
        checkNotNullOrEmpty(response, "addressLoadResponse");
        SUBUDSResponseProcessor.validateResponse(module, response);
        final int base = RESPONSE_NON_DATA_BYTES;
        final boolean ok;
        if (did < 0) {
            ok = response.length >= base + 2
                    && response[base] == LOAD_ADDRESS_RESPONSE
                    && response[base + 1] == CLEAR_DEFINITIONS;
        }
        else {
            ok = response.length >= base + 4
                    && response[base] == LOAD_ADDRESS_RESPONSE
                    && response[base + 1] == DEFINE_BY_MEMORY_ADDRESS
                    && (response[base + 2] & 0xFF) == ((did >> 8) & 0xFF)
                    && (response[base + 3] & 0xFF) == (did & 0xFF);
        }
        if (!ok) {
            throw new InvalidResponseException(
                    "Unexpected load address response: " + asHex(response));
        }
    }

    @Override
    public byte[] constructReadAddressRequest(Module module, int[] dids) {
        checkNotNull(module, "module");
        this.module = module;
        final byte[] content = new byte[dids.length * 2];
        for (int i = 0; i < dids.length; i++) {
            content[2 * i] = (byte) (dids[i] >> 8);
            content[2 * i + 1] = (byte) dids[i];
        }
        return buildRequest(READ_DID_COMMAND, content);
    }

    @Override
    public byte[] constructReadAddressRequest(Module module, byte[][] addresses) {
        throw new UnsupportedProtocolException(
                "SUBUDS reads RAM through dynamically defined identifiers only");
    }

    @Override
    public byte[] constructReadMemoryRequest(Module module, byte[] address, int numBytes) {
        throw new UnsupportedProtocolException(
                "Read memory command is not supported (it needs SecurityAccess) for address: " +
                asHex(address));
    }

    @Override
    public byte[] constructWriteMemoryRequest(Module module, byte[] address, byte[] values) {
        throw new UnsupportedProtocolException(
                "Write memory command is not supported for address: " + asHex(address));
    }

    @Override
    public byte[] constructWriteAddressRequest(Module module, byte[] address, byte value) {
        throw new UnsupportedProtocolException(
                "Write address command is not supported for address: " + asHex(address));
    }

    @Override
    public byte[] constructEcuResetRequest(Module module, int resetCode) {
        throw new UnsupportedProtocolException(
                "ECU reset is not supported by the SUBUDS protocol; use SSM");
    }

    @Override
    public void checkValidEcuResetResponse(byte[] processedResponse) {
        throw new UnsupportedProtocolException(
                "ECU reset is not supported by the SUBUDS protocol; use SSM");
    }

    @Override
    public void checkValidWriteResponse(byte[] data, byte[] processedResponse) {
    }

    @Override
    public byte[] preprocessResponse(byte[] request, byte[] response, PollingState pollState) {
        return SUBUDSResponseProcessor.filterRequestFromResponse(request, response, pollState);
    }

    @Override
    public byte[] parseResponseData(byte[] processedResponse) {
        checkNotNullOrEmpty(processedResponse, "processedResponse");
        return SUBUDSResponseProcessor.extractResponseData(module, processedResponse);
    }

    @Override
    public void checkValidEcuInitResponse(byte[] processedResponse) {
        checkNotNullOrEmpty(processedResponse, "processedResponse");
        SUBUDSResponseProcessor.validateResponse(module, processedResponse);
        if (processedResponse[RESPONSE_NON_DATA_BYTES] != ECU_INIT_RESPONSE) {
            throw new InvalidResponseException(
                    "Unexpected ECU init response: " + asHex(processedResponse));
        }
    }

    /** The SSM init reply; the ECU ID matches the one the SSM protocol shows. */
    @Override
    public EcuInit parseEcuInitResponse(byte[] processedResponse) {
        checkValidEcuInitResponse(processedResponse);
        return new SSMEcuInit(parseResponseData(processedResponse));
    }

    @Override
    public ConnectionProperties getDefaultConnectionProperties() {
        return new ConnectionProperties() {

            public int getBaudRate() {
                return 500000;
            }

            public void setBaudRate(int b) {

            }

            public int getDataBits() {
                return 8;
            }

            public int getStopBits() {
                return 1;
            }

            public int getParity() {
                return 0;
            }

            public int getConnectTimeout() {
                return 2000;
            }

            public int getSendTimeout() {
                return 55;
            }
        };
    }

    Module getModule() {
        return module;
    }

    private byte[] buildRequest(byte command, byte... content) {
        bb.reset();
        try {
            bb.write(module.getTester());
            bb.write(command);
            bb.write(content);
        }
        catch (IOException e) {
            e.printStackTrace();
        }
        return bb.toByteArray();
    }
}
