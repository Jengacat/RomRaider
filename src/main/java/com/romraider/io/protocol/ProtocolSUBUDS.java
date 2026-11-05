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

package com.romraider.io.protocol;

import com.romraider.logger.ecu.definition.Module;

/**
 * Subaru UDS logging over ISO 15765 (CAN): RAM parameters are read through
 * dynamically defined data identifiers.
 * The extended diagnostic session (0x10 0x03) allows DynamicallyDefineDataIdentifier
 * (0x2C) by memory address for RAM, without SecurityAccess, and ReadDataByIdentifier
 * (0x22) reads the defined identifiers back.
 */
public interface ProtocolSUBUDS extends Protocol {

    /** 0x10 0x03, extended diagnostic session. */
    byte[] constructStartDiagRequest(Module module);

    /** 0x10 0x01, back to the default session (also clears the definitions in the ECU). */
    byte[] constructEcuStopRequest(Module module);

    /** 0x3E 0x00, TesterPresent. */
    byte[] constructTesterPresentRequest(Module module);

    /** 0x2C 0x03, clear all dynamically defined identifiers. */
    byte[] constructClearAddressRequest(Module module);

    /**
     * 0x2C 0x02 DID 0x14 then 4-byte address and 1-byte length per range.
     * @param did - dynamic identifier, 0xF300 - 0xF313
     * @param addresses - start address of each range (4 bytes each)
     * @param lengths - length of each range
     */
    byte[] constructLoadAddressRequest(Module module, int did, byte[][] addresses, int[] lengths);

    /** Checks a 0x6C 0x02 DID reply (or 0x6C 0x03 for a clear, did &lt; 0). */
    void validateLoadAddressResponse(byte[] response, int did);

    /** 0x22 followed by each DID. */
    byte[] constructReadAddressRequest(Module module, int[] dids);
}
