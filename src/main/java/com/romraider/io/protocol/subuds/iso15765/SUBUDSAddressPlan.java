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

import static com.romraider.util.ParamChecker.checkNotNullOrEmpty;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import com.romraider.logger.ecu.comms.query.EcuQuery;
import com.romraider.logger.ecu.comms.query.EcuQueryData;
import com.romraider.logger.ecu.exception.UnsupportedProtocolException;

/**
 * How a set of RAM queries is packed into dynamically defined identifiers.
 * <p>
 * Every byte the queries need is collected, neighbouring bytes are merged into
 * ranges, and the ranges are spread over identifiers 0xF300 - 0xF313. The ECU
 * limits (Subaru FA20DIT, service 0x2C sub-function 0x02, format 0x14):
 * <ul>
 * <li>up to 10 ranges per identifier, 1 - 100 bytes each, at most 0x3FE bytes per identifier;</li>
 * <li>20 identifiers;</li>
 * <li>RAM 0xFFF80000 - 0xFFF9FFFE (the end test is start + length &lt;= 0xFFF9FFFF);</li>
 * <li>the 0x22 reply, including its 0x62 byte, at most 0xFFF bytes.</li>
 * </ul>
 * Logger definitions give RAM addresses as 3 bytes (0xF8CD54); an address of
 * 3 bytes or fewer with the top bit set gets 0xFF as its high byte, the same
 * convention as the NCS protocol.
 */
public final class SUBUDSAddressPlan {
    public static final int FIRST_DID = 0xF300;
    public static final int MAX_DIDS = 20;
    public static final int MAX_RANGES_PER_DID = 10;
    public static final int MAX_RANGE_LENGTH = 100;
    public static final int MAX_DID_BYTES = 0x3FE;
    public static final int MAX_REPLY_BYTES = 0xFFF;
    public static final long RAM_START = 0xFFF80000L;
    public static final long RAM_LIMIT = 0xFFF9FFFFL;   // start + length must not exceed this
    /** Bytes between two needed bytes that are read anyway to keep them in one range. */
    public static final int MERGE_GAP = 2;

    private final int[] dids;
    private final List<List<long[]>> ranges;           // per identifier: {start, length}
    private final Map<String, int[]> positions;        // query hex -> offset of each byte in the reply
    private final int replyLength;                     // 0x62 + (DID + data) per identifier

    private SUBUDSAddressPlan(int[] dids, List<List<long[]>> ranges,
            Map<String, int[]> positions, int replyLength) {
        this.dids = dids;
        this.ranges = ranges;
        this.positions = positions;
        this.replyLength = replyLength;
    }

    public static SUBUDSAddressPlan build(Collection<EcuQuery> queries) {
        checkNotNullOrEmpty(queries, "queries");
        final Map<String, long[]> queryBytes = new LinkedHashMap<String, long[]>();
        final TreeSet<Long> needed = new TreeSet<Long>();
        for (EcuQuery query : queries) {
            if (queryBytes.containsKey(query.getHex())) {
                continue;
            }
            final long[] bytes = byteAddresses(query);
            for (long b : bytes) {
                if (b < RAM_START || b + 1 > RAM_LIMIT) {
                    throw new UnsupportedProtocolException(String.format(
                            "Address 0x%08X of %s is outside the ECU RAM that can be logged " +
                            "(0xFFF80000 - 0xFFF9FFFE)", b, query.getLoggerData().getName()));
                }
                needed.add(b);
            }
            queryBytes.put(query.getHex(), bytes);
        }

        // merge the needed bytes into ranges
        final List<long[]> merged = new ArrayList<long[]>();
        long[] current = null;
        for (long b : needed) {
            if (current != null && b - (current[0] + current[1]) <= MERGE_GAP
                    && b - current[0] + 1 <= MAX_RANGE_LENGTH) {
                current[1] = b - current[0] + 1;
            }
            else {
                current = new long[]{b, 1};
                merged.add(current);
            }
        }

        // spread the ranges over identifiers
        final List<List<long[]>> perDid = new ArrayList<List<long[]>>();
        List<long[]> did = null;
        int didBytes = 0;
        for (long[] r : merged) {
            if (did == null || did.size() == MAX_RANGES_PER_DID
                    || didBytes + r[1] > MAX_DID_BYTES) {
                did = new ArrayList<long[]>();
                perDid.add(did);
                didBytes = 0;
            }
            did.add(r);
            didBytes += r[1];
        }
        if (perDid.size() > MAX_DIDS) {
            throw new UnsupportedProtocolException(String.format(
                    "Too many parameters selected: %d address ranges need %d identifiers, the ECU has %d",
                    merged.size(), perDid.size(), MAX_DIDS));
        }

        // reply offset of every needed byte
        final Map<Long, Integer> offsetOf = new HashMap<Long, Integer>();
        int offset = 1;                                    // 0x62
        final int[] didIds = new int[perDid.size()];
        for (int i = 0; i < perDid.size(); i++) {
            didIds[i] = FIRST_DID + i;
            offset += 2;                                   // DID echo
            for (long[] r : perDid.get(i)) {
                for (int k = 0; k < r[1]; k++) {
                    offsetOf.put(r[0] + k, offset + k);
                }
                offset += (int) r[1];
            }
        }
        if (offset > MAX_REPLY_BYTES) {
            throw new UnsupportedProtocolException(String.format(
                    "Too many parameters selected: the reply would be %d bytes, the ECU sends at most %d",
                    offset, MAX_REPLY_BYTES));
        }

        final Map<String, int[]> positions = new HashMap<String, int[]>();
        for (Map.Entry<String, long[]> e : queryBytes.entrySet()) {
            final long[] bytes = e.getValue();
            final int[] pos = new int[bytes.length];
            for (int k = 0; k < bytes.length; k++) {
                pos[k] = offsetOf.get(bytes[k]);
            }
            positions.put(e.getKey(), pos);
        }
        return new SUBUDSAddressPlan(didIds, perDid, positions, offset);
    }

    /**
     * The RAM bytes a query reads, in order: its addresses if it lists one per
     * byte, otherwise consecutive bytes from its first address.
     */
    static long[] byteAddresses(EcuQuery query) {
        final String[] addresses = query.getAddresses();
        final int dataLength = EcuQueryData.getDataLength(query);
        final long[] bytes = new long[dataLength];
        if (addresses.length >= dataLength) {
            for (int k = 0; k < dataLength; k++) {
                bytes[k] = expand(addresses[k]);
            }
        }
        else {
            final long start = expand(addresses[0]);
            for (int k = 0; k < dataLength; k++) {
                bytes[k] = start + k;
            }
        }
        return bytes;
    }

    /** 0xF8CD54 -> 0xFFF8CD54 (3-byte RAM address with the top bit set). */
    static long expand(String address) {
        String hex = address.trim();
        if (hex.startsWith("0x") || hex.startsWith("0X")) {
            hex = hex.substring(2);
        }
        long value = Long.parseLong(hex, 16);
        if (hex.length() <= 6 && (value & 0x800000L) != 0) {
            value |= 0xFF000000L;
        }
        return value;
    }

    public int[] getDids() {
        return dids.clone();
    }

    /** {start, length} of each range of identifier number i (0 = 0xF300). */
    public List<long[]> getRanges(int i) {
        return Collections.unmodifiableList(ranges.get(i));
    }

    public int getRangeCount() {
        int n = 0;
        for (List<long[]> r : ranges) {
            n += r.size();
        }
        return n;
    }

    /** Offset of each data byte of the query within the 0x62 reply (the 0x62 byte is offset 0). */
    public int[] getPositions(EcuQuery query) {
        return positions.get(query.getHex());
    }

    /** Length of the 0x62 reply, without the CAN ID. */
    public int getReplyLength() {
        return replyLength;
    }
}
