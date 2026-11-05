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

package com.romraider.logger.ecu.comms.io.connection;

import static com.romraider.util.HexUtil.asHex;
import static com.romraider.util.ParamChecker.checkNotNull;
import static org.apache.log4j.Logger.getLogger;

import java.util.Collection;
import java.util.Map;
import java.util.ResourceBundle;

import org.apache.log4j.Logger;

import com.romraider.io.connection.ConnectionManager;
import com.romraider.io.protocol.ProtocolFactory;
import com.romraider.io.protocol.subuds.iso15765.SUBUDSAddressPlan;
import com.romraider.logger.ecu.comms.io.protocol.LoggerProtocolSUBUDS;
import com.romraider.logger.ecu.comms.manager.PollingState;
import com.romraider.logger.ecu.comms.manager.PollingStateImpl;
import com.romraider.logger.ecu.comms.query.EcuInitCallback;
import com.romraider.logger.ecu.comms.query.EcuQuery;
import com.romraider.logger.ecu.definition.Module;
import com.romraider.logger.ecu.exception.SerialCommunicationException;
import com.romraider.util.ResourceUtil;
import com.romraider.util.SettingsManager;

/**
 * Subaru UDS logging over CAN: open the extended session, identify the ECU with
 * the SSM init request, define dynamic identifiers by RAM address once per
 * parameter selection, then read them all with one 0x22 request per poll.
 * If the ECU drops the session (its definitions are then cleared and a read
 * answers 7F 22 31), the session is opened again and the identifiers redefined.
 */
public final class SUBUDSLoggerConnection implements LoggerConnection {
    private static final Logger LOGGER = getLogger(SUBUDSLoggerConnection.class);
    private static final ResourceBundle rb = new ResourceUtil().getBundle(
            SUBUDSLoggerConnection.class.getName());
    /** CAN request limit used by the other CAN protocols. */
    private static final int MAX_REQUEST = 99;
    private final LoggerProtocolSUBUDS protocol;
    private final ConnectionManager manager;
    private SUBUDSAddressPlan plan;
    private String planKey;
    private boolean commsStarted;

    public SUBUDSLoggerConnection(ConnectionManager manager) {
        this(manager, (LoggerProtocolSUBUDS) ProtocolFactory.getProtocol(
                SettingsManager.getSettings().getLoggerProtocol(),
                SettingsManager.getSettings().getTransportProtocol()));
    }

    SUBUDSLoggerConnection(ConnectionManager manager, LoggerProtocolSUBUDS protocol) {
        checkNotNull(manager, "manager");
        checkNotNull(protocol, "protocol");
        this.manager = manager;
        this.protocol = protocol;
        commsStarted = false;
    }

    @Override
    public void ecuReset(Module module, int resetCode) {
        protocol.constructEcuResetRequest(module, resetCode);   // throws: not supported
    }

    @Override
    public void ecuInit(EcuInitCallback callback, Module module) {
        if (!commsStarted) open(module);
        final byte[] request = protocol.constructEcuInitRequest(module);
        if (LOGGER.isDebugEnabled())
            LOGGER.debug(String.format("%s Init Request  ---> %s", module, asHex(request)));
        final byte[] response = manager.send(request);
        final byte[] processedResponse = protocol.preprocessResponse(
                request, response, new PollingStateImpl());
        if (LOGGER.isDebugEnabled())
            LOGGER.debug(String.format("%s Init Response <--- %s", module, asHex(processedResponse)));
        protocol.processEcuInitResponse(callback, processedResponse);
    }

    @Override
    public final void sendAddressReads(
            Collection<EcuQuery> queries,
            Module module,
            PollingState pollState) {

        if (!commsStarted) open(module);
        final String key = queryKey(queries);
        if (plan == null || !key.equals(planKey)) {
            loadAddresses(queries, module);
            planKey = key;
        }
        byte[] response = readAddresses(module, pollState);
        if (protocol.isDefinitionLost(response)) {
            LOGGER.info(module + " dynamic identifiers lost (session timed out), defining them again");
            open(module);
            loadAddresses(queries, module);
            response = readAddresses(module, pollState);
        }
        protocol.processReadAddressResponses(queries, response, plan);
    }

    @Override
    public void clearLine() {
        manager.clearLine();
    }

    @Override
    public void open(Module module) {
        // Extended diagnostic session; the stop request returns to the default session on close
        final byte[] start = protocol.constructStartDiagRequest(module);
        final byte[] stop = protocol.constructEcuStopRequest(module);
        manager.open(start, stop);
        commsStarted = true;
        plan = null;
        planKey = null;
    }

    @Override
    public void close() {
        commsStarted = false;
        plan = null;
        planKey = null;
        manager.close();
    }

    @Override
    public void sendAddressWrites(Map<EcuQuery, byte[]> writeQueries, Module module) {
        throw new UnsupportedOperationException();
    }

    private void loadAddresses(Collection<EcuQuery> queries, Module module) {
        plan = protocol.buildAddressPlan(queries);
        byte[] request = protocol.constructClearAddressRequest(module);
        if (LOGGER.isDebugEnabled())
            LOGGER.debug(String.format("%s Clear address request  ---> %s", module, asHex(request)));
        byte[] response = manager.send(request);
        if (LOGGER.isDebugEnabled())
            LOGGER.debug(String.format("%s Clear address response <--- %s", module, asHex(response)));
        protocol.validateLoadAddressResponse(response, -1);

        final int[] dids = plan.getDids();
        final byte[][] requests = protocol.constructLoadAddressRequests(module, plan);
        for (int i = 0; i < requests.length; i++) {
            request = requests[i];
            if (request.length > MAX_REQUEST) {
                throw new SerialCommunicationException(rb.getString("TOOLARGE"));
            }
            if (LOGGER.isDebugEnabled())
                LOGGER.debug(String.format("%s Load address request  ---> %s", module, asHex(request)));
            response = manager.send(request);
            if (LOGGER.isDebugEnabled())
                LOGGER.debug(String.format("%s Load address response <--- %s", module, asHex(response)));
            protocol.validateLoadAddressResponse(response, dids[i]);
        }
    }

    private byte[] readAddresses(Module module, PollingState pollState) {
        final byte[] request = protocol.constructReadAddressRequest(module, plan);
        final byte[] response = protocol.constructReadAddressResponse(plan);
        if (LOGGER.isTraceEnabled())
            LOGGER.trace(String.format("%s Read request  ---> %s", module, asHex(request)));
        manager.send(request, response, pollState);
        final byte[] processedResponse = protocol.preprocessResponse(request, response, pollState);
        if (LOGGER.isTraceEnabled())
            LOGGER.trace(String.format("%s Read response <--- %s", module, asHex(processedResponse)));
        return processedResponse;
    }

    /** The selected parameters, in order; a different key means the identifiers must be redefined. */
    private static String queryKey(Collection<EcuQuery> queries) {
        final StringBuilder sb = new StringBuilder();
        for (EcuQuery query : queries) {
            sb.append(query.getHex()).append(':')
              .append(query.getLoggerData().getSelectedConvertor().getDataType()).append(';');
        }
        return sb.toString();
    }
}
