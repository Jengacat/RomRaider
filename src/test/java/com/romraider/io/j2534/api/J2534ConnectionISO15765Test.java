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

package com.romraider.io.j2534.api;

import static com.romraider.util.HexUtil.asBytes;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class J2534ConnectionISO15765Test {

    @Test
    public void responsePendingIsRecognised() {
        assertTrue(J2534ConnectionISO15765.isResponsePending(asBytes("000007E87F1978")));
        assertTrue(J2534ConnectionISO15765.isResponsePending(asBytes("000007E87F2278")));
    }

    @Test
    public void otherRepliesAreNotPending() {
        assertFalse(J2534ConnectionISO15765.isResponsePending(asBytes("000007E87F1922")));   // a real refusal
        assertFalse(J2534ConnectionISO15765.isResponsePending(asBytes("000007E859027F")));
        assertFalse(J2534ConnectionISO15765.isResponsePending(asBytes("000007E85902780078")));
        assertFalse(J2534ConnectionISO15765.isResponsePending(null));
    }
}
