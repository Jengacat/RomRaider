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

package com.romraider.logger.ecu.comms.io.protocol;

import java.util.Collection;

import com.romraider.io.protocol.subuds.iso15765.SUBUDSAddressPlan;
import com.romraider.logger.ecu.comms.query.EcuQuery;
import com.romraider.logger.ecu.definition.Module;

public interface LoggerProtocolSUBUDS extends LoggerProtocol {

    byte[] constructStartDiagRequest(Module module);

    byte[] constructEcuStopRequest(Module module);

    byte[] constructTesterPresentRequest(Module module);

    byte[] constructClearAddressRequest(Module module);

    SUBUDSAddressPlan buildAddressPlan(Collection<EcuQuery> queries);

    byte[][] constructLoadAddressRequests(Module module, SUBUDSAddressPlan plan);

    void validateLoadAddressResponse(byte[] response, int did);

    byte[] constructReadAddressRequest(Module module, SUBUDSAddressPlan plan);

    byte[] constructReadAddressResponse(SUBUDSAddressPlan plan);

    boolean isDefinitionLost(byte[] response);

    void processReadAddressResponses(
            Collection<EcuQuery> queries, byte[] response, SUBUDSAddressPlan plan);

    Collection<EcuQuery> filterDuplicates(Collection<EcuQuery> queries);
}
