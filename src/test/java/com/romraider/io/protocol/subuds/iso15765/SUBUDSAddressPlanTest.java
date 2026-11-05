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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.junit.Test;

import com.romraider.Settings;
import com.romraider.logger.ecu.comms.query.EcuQuery;
import com.romraider.logger.ecu.comms.query.EcuQueryImpl;
import com.romraider.logger.ecu.definition.EcuAddressImpl;
import com.romraider.logger.ecu.definition.EcuDataConvertor;
import com.romraider.logger.ecu.definition.EcuParameterConvertorImpl;
import com.romraider.logger.ecu.definition.EcuParameterImpl;
import com.romraider.logger.ecu.definition.xml.ConverterMaxMinDefaults;
import com.romraider.logger.ecu.exception.UnsupportedProtocolException;

public class SUBUDSAddressPlanTest {

    static EcuQuery query(String id, String address, int length, String type) {
        final EcuDataConvertor convertor = new EcuParameterConvertorImpl(
                "raw", "x", "0", -1, type, Settings.Endian.BIG,
                new HashMap<String, String>(), ConverterMaxMinDefaults.getDefault());
        return new EcuQueryImpl(new EcuParameterImpl(id, id, id,
                new EcuAddressImpl(address, length, -1), null, null, null,
                new EcuDataConvertor[]{convertor}));
    }

    static EcuQuery byteAt(long ram) {
        return query("B" + Long.toHexString(ram), String.format("0x%06X", ram & 0xFFFFFF), 1, "uint8");
    }

    @Test
    public void threeByteRamAddressesGetTheHighByte() {
        assertEquals(0xFFF8CD54L, SUBUDSAddressPlan.expand("0xF8CD54"));
        assertEquals(0xFFF80000L, SUBUDSAddressPlan.expand("F80000"));
        assertEquals(0x00000EL, SUBUDSAddressPlan.expand("0x00000E"));
        assertEquals(0xFFF8CD54L, SUBUDSAddressPlan.expand("0xFFF8CD54"));
    }

    @Test
    public void wordQueryReadsConsecutiveBytes() {
        final List<EcuQuery> queries = new ArrayList<EcuQuery>();
        queries.add(query("J01", "0xF8B4FA", 2, "uint16"));
        final SUBUDSAddressPlan plan = SUBUDSAddressPlan.build(queries);
        assertEquals(1, plan.getRangeCount());
        assertArrayEquals(new long[]{0xFFF8B4FAL, 2}, plan.getRanges(0).get(0));
        assertArrayEquals(new int[]{3, 4}, plan.getPositions(queries.get(0)));
        assertEquals(5, plan.getReplyLength());                  // 62 F3 00 hi lo
    }

    @Test
    public void nearbyBytesShareARange() {
        final List<EcuQuery> queries = new ArrayList<EcuQuery>();
        queries.add(byteAt(0xFFF88000L));
        queries.add(byteAt(0xFFF88003L));                        // gap of 2: merged
        queries.add(byteAt(0xFFF88007L));                        // gap of 3: new range
        final SUBUDSAddressPlan plan = SUBUDSAddressPlan.build(queries);
        assertEquals(2, plan.getRangeCount());
        assertArrayEquals(new long[]{0xFFF88000L, 4}, plan.getRanges(0).get(0));
        assertArrayEquals(new long[]{0xFFF88007L, 1}, plan.getRanges(0).get(1));
        assertArrayEquals(new int[]{3}, plan.getPositions(queries.get(0)));
        assertArrayEquals(new int[]{6}, plan.getPositions(queries.get(1)));
        assertArrayEquals(new int[]{7}, plan.getPositions(queries.get(2)));
    }

    @Test
    public void tenRangesPerIdentifier() {
        final List<EcuQuery> queries = new ArrayList<EcuQuery>();
        for (int i = 0; i < 11; i++) {
            queries.add(byteAt(0xFFF88000L + 0x10 * i));
        }
        final SUBUDSAddressPlan plan = SUBUDSAddressPlan.build(queries);
        assertArrayEquals(new int[]{0xF300, 0xF301}, plan.getDids());
        assertEquals(10, plan.getRanges(0).size());
        assertEquals(1, plan.getRanges(1).size());
        // 62, F300 + 10 bytes, F301 + 1 byte
        assertEquals(1 + 2 + 10 + 2 + 1, plan.getReplyLength());
        assertArrayEquals(new int[]{15}, plan.getPositions(queries.get(10)));
    }

    @Test
    public void rangesAreAtMostOneHundredBytes() {
        final List<EcuQuery> queries = new ArrayList<EcuQuery>();
        for (int i = 0; i < 101; i++) {
            queries.add(byteAt(0xFFF88000L + i));
        }
        final SUBUDSAddressPlan plan = SUBUDSAddressPlan.build(queries);
        assertEquals(2, plan.getRangeCount());
        assertEquals(100L, plan.getRanges(0).get(0)[1]);
        assertEquals(1L, plan.getRanges(0).get(1)[1]);
    }

    @Test
    public void twentyIdentifiersAtMost() {
        final List<EcuQuery> queries = new ArrayList<EcuQuery>();
        for (int i = 0; i < 200; i++) {
            queries.add(byteAt(0xFFF88000L + 0x10 * i));
        }
        assertEquals(20, SUBUDSAddressPlan.build(queries).getDids().length);
        queries.add(byteAt(0xFFF88000L + 0x10 * 200));
        try {
            SUBUDSAddressPlan.build(queries);
            fail("201 ranges accepted");
        }
        catch (UnsupportedProtocolException expected) {
        }
    }

    @Test
    public void replyLimit() {
        final List<EcuQuery> queries = new ArrayList<EcuQuery>();
        for (int i = 0; i < 1025; i++) {                          // 4100 bytes
            queries.add(query("D" + i, String.format("0x%06X", 0xF88000 + 4 * i), 1, "uint32"));
        }
        try {
            SUBUDSAddressPlan.build(queries);
            fail("reply over 0xFFF bytes accepted");
        }
        catch (UnsupportedProtocolException expected) {
        }
    }

    @Test
    public void ramWindow() {
        final List<EcuQuery> queries = new ArrayList<EcuQuery>();
        queries.add(byteAt(0xFFF80000L));
        queries.add(byteAt(0xFFF9FFFEL));
        SUBUDSAddressPlan.build(queries);
        for (long bad : new long[]{0xFFF7FFFFL, 0xFFF9FFFFL}) {
            queries.clear();
            queries.add(byteAt(bad));
            try {
                SUBUDSAddressPlan.build(queries);
                fail(String.format("%08X accepted", bad));
            }
            catch (UnsupportedProtocolException expected) {
            }
        }
    }
}
