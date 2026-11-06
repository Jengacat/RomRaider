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
 * Subaru UDS logging over CAN: RAM read through dynamically defined identifiers.
 */
public interface ProtocolSUBUDS extends Protocol {

    byte[] constructStartDiagRequest(Module module);

    byte[] constructEcuStopRequest(Module module);

    byte[] constructTesterPresentRequest(Module module);

    byte[] constructClearAddressRequest(Module module);

    byte[] constructLoadAddressRequest(Module module, int did, byte[][] addresses, int[] lengths);

    void validateLoadAddressResponse(byte[] response, int did);

    byte[] constructReadAddressRequest(Module module, int[] dids);
}
