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

import static com.romraider.util.HexUtil.asBytes;
import static com.romraider.util.HexUtil.asHex;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

import org.junit.Test;

import com.romraider.Settings;
import com.romraider.io.connection.ConnectionManager;
import com.romraider.io.protocol.subuds.iso15765.SUBUDSLoggerProtocol;
import com.romraider.logger.ecu.comms.manager.PollingState;
import com.romraider.logger.ecu.comms.manager.PollingStateImpl;
import com.romraider.logger.ecu.comms.query.EcuInit;
import com.romraider.logger.ecu.comms.query.EcuInitCallback;
import com.romraider.logger.ecu.comms.query.EcuQuery;
import com.romraider.logger.ecu.comms.query.EcuQueryImpl;
import com.romraider.logger.ecu.definition.EcuAddressImpl;
import com.romraider.logger.ecu.definition.EcuDataConvertor;
import com.romraider.logger.ecu.definition.EcuParameterConvertorImpl;
import com.romraider.logger.ecu.definition.EcuParameterImpl;
import com.romraider.logger.ecu.definition.Module;
import com.romraider.logger.ecu.definition.xml.ConverterMaxMinDefaults;

/**
 * The connection against replies recorded from the ECU's own code: each request
 * below was built by SUBUDSLoggerProtocol and run through the 2015 WRX ECU
 * (LF75404S, ECU ID 8B145A7A07) software in an SH-2A emulator, with known
 * values in RAM. Parameters are real FA20DIT RAM addresses (engine speed,
 * coolant, mass airflow, target torque, feedback knock, rail pressure, intake
 * cam learned rest).
 */
public class SUBUDSLoggerConnectionTest {

    private static final String DEFINE =
            "2C02F30014FFF8158202FFF8888402FFF8A79A01FFF8A97301FFF8AECA02FFF8B4FA06";
    private static final String READ_REPLY = "62F3006F811234FE5A01901B58AABB029C";

    private static final Module ECU = new Module("ecu",
            new byte[]{0, 0, 7, (byte) 0xE8}, "Engine Control Unit",
            new byte[]{0, 0, 7, (byte) 0xE0}, true);

    /** Answers from a recorded request/reply transcript; records what was sent. */
    static final class TranscriptManager implements ConnectionManager {
        final LinkedList<String[]> script = new LinkedList<String[]>();
        final List<String> sent = new ArrayList<String>();

        TranscriptManager expect(String request, String reply) {
            script.add(new String[]{request, reply});
            return this;
        }

        private byte[] answer(byte[] request) {
            final String req = asHex(request).substring(8);          // drop 000007E0
            sent.add(req);
            final String[] next = script.poll();
            assertNotNull("unexpected request " + req, next);
            assertEquals("request", next[0], req);
            return asBytes("000007E8" + next[1]);
        }

        public void open(byte[] start, byte[] stop) {
            answer(start);
        }

        public void send(byte[] request, byte[] response, PollingState pollState) {
            final byte[] reply = answer(request);
            System.arraycopy(reply, 0, response, 0, reply.length);
        }

        public byte[] send(byte[] request) {
            return answer(request);
        }

        public void clearLine() {
        }

        public void close() {
        }
    }

    private static EcuQuery query(String id, String address, int length, String type) {
        final EcuDataConvertor convertor = new EcuParameterConvertorImpl(
                "raw", "x", "0", -1, type, Settings.Endian.BIG,
                new HashMap<String, String>(), ConverterMaxMinDefaults.getDefault());
        return new EcuQueryImpl(new EcuParameterImpl(id, id, id,
                new EcuAddressImpl(address, length, -1), null, null, null,
                new EcuDataConvertor[]{convertor}));
    }

    private static List<EcuQuery> queries() {
        final List<EcuQuery> q = new ArrayList<EcuQuery>();
        q.add(query("J01", "0xF8B4FA", 2, "uint16"));   // engine speed, rpm x 4
        q.add(query("J02", "0xF8A973", 1, "uint8"));    // coolant
        q.add(query("J05", "0xF8B4FE", 2, "uint16"));   // mass airflow
        q.add(query("L01", "0xF8AECA", 2, "uint16"));   // target torque
        q.add(query("L04", "0xF8A79A", 1, "uint8"));    // feedback knock correction
        q.add(query("L09", "0xF88884", 2, "uint16"));   // fuel rail pressure
        q.add(query("V01", "0xF81582", 2, "uint16"));   // intake cam learned rest
        return q;
    }

    private static void assertValues(List<EcuQuery> q) {
        assertEquals(0x1B58, q.get(0).getResponse(), 0);
        assertEquals(0x5A, q.get(1).getResponse(), 0);
        assertEquals(0x029C, q.get(2).getResponse(), 0);
        assertEquals(0x0190, q.get(3).getResponse(), 0);
        assertEquals(0xFE, q.get(4).getResponse(), 0);
        assertEquals(0x1234, q.get(5).getResponse(), 0);
        assertEquals(0x6F81, q.get(6).getResponse(), 0);
    }

    @Test
    public void ecuIdIsTheSsmOne() {
        final TranscriptManager m = new TranscriptManager()
                .expect("1003", "5003")
                .expect("AA", "EAA3100F8B145A7A074180800000000000");
        final SUBUDSLoggerConnection c = new SUBUDSLoggerConnection(m, new SUBUDSLoggerProtocol());
        final String[] id = new String[1];
        c.ecuInit(new EcuInitCallback() {
            public void callback(EcuInit ecuInit) {
                id[0] = ecuInit.getEcuId();
            }
        }, ECU);
        assertEquals("8B145A7A07", id[0]);
    }

    @Test
    public void defineOnceThenReadEachPoll() {
        final TranscriptManager m = new TranscriptManager()
                .expect("1003", "5003")
                .expect("2C03", "6C03")
                .expect(DEFINE, "6C02F300")
                .expect("22F300", READ_REPLY)
                .expect("22F300", READ_REPLY);
        final SUBUDSLoggerConnection c = new SUBUDSLoggerConnection(m, new SUBUDSLoggerProtocol());
        final List<EcuQuery> q = queries();
        final PollingState poll = new PollingStateImpl();
        c.sendAddressReads(q, ECU, poll);
        assertValues(q);
        c.sendAddressReads(q, ECU, poll);                   // same selection: no redefine
        assertValues(q);
        assertTrue(m.script.isEmpty());
    }

    @Test
    public void redefinesWhenTheSelectionChanges() {
        final TranscriptManager m = new TranscriptManager()
                .expect("1003", "5003")
                .expect("2C03", "6C03")
                .expect(DEFINE, "6C02F300")
                .expect("22F300", READ_REPLY)
                .expect("2C03", "6C03")
                .expect("2C02F30014FFF8A97301", "6C02F300")
                .expect("22F300", "62F3005A");
        final SUBUDSLoggerConnection c = new SUBUDSLoggerConnection(m, new SUBUDSLoggerProtocol());
        final List<EcuQuery> q = queries();
        final PollingState poll = new PollingStateImpl();
        c.sendAddressReads(q, ECU, poll);
        final List<EcuQuery> coolantOnly = new ArrayList<EcuQuery>();
        coolantOnly.add(q.get(1));
        c.sendAddressReads(coolantOnly, ECU, poll);
        assertEquals(0x5A, coolantOnly.get(0).getResponse(), 0);
        assertTrue(m.script.isEmpty());
    }

    @Test
    public void recoversWhenTheSessionDrops() {
        // After the ECU's session timeout its definitions are gone and a read answers 7F 22 31.
        final TranscriptManager m = new TranscriptManager()
                .expect("1003", "5003")
                .expect("2C03", "6C03")
                .expect(DEFINE, "6C02F300")
                .expect("22F300", READ_REPLY)
                .expect("22F300", "7F2231")
                .expect("1003", "5003")
                .expect("2C03", "6C03")
                .expect(DEFINE, "6C02F300")
                .expect("22F300", READ_REPLY);
        final SUBUDSLoggerConnection c = new SUBUDSLoggerConnection(m, new SUBUDSLoggerProtocol());
        final List<EcuQuery> q = queries();
        final PollingState poll = new PollingStateImpl();
        c.sendAddressReads(q, ECU, poll);
        c.sendAddressReads(q, ECU, poll);
        assertValues(q);
        assertTrue(m.script.isEmpty());
    }

    @Test(expected = com.romraider.logger.ecu.exception.InvalidResponseException.class)
    public void refusedDefinitionIsReported() {
        final TranscriptManager m = new TranscriptManager()
                .expect("1003", "5003")
                .expect("2C03", "6C03")
                .expect(DEFINE, "7F2C31");
        new SUBUDSLoggerConnection(m, new SUBUDSLoggerProtocol())
                .sendAddressReads(queries(), ECU, new PollingStateImpl());
    }
}
