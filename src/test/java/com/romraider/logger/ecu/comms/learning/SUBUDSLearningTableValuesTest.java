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

package com.romraider.logger.ecu.comms.learning;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.romraider.Settings;
import com.romraider.logger.ecu.comms.query.EcuQuery;

public class SUBUDSLearningTableValuesTest {

    private static File definition() throws Exception {
        final File f = File.createTempFile("subuds-learning", ".xml");
        f.deleteOnExit();
        final FileWriter w = new FileWriter(f);
        w.write("<logger><protocols>"
                + "<protocol id=\"SSM\"><learning flkcrows=\"9\"><learningecu ids=\"AAAAAAAAAA\" flkc=\"0x1\"/></learning></protocol>"
                + "<protocol id=\"SUBUDS\"><ecuparams><ecuparam id=\"J01\"><ecu id=\"8B145A7A07\"><address>0xF8B4FA</address></ecu></ecuparam></ecuparams>"
                + "<learning flkcrows=\"7\" flkccols=\"5\" flkcexpr=\"x/256*0.3515625\" flkcrowaxis=\"RPM %d\">"
                + "<learningecu ids=\"A529A02007,8B145A7A07\" flkc=\"0xF81414\" aflearning=\"0xF81466\" dam=\"0xF811AE\"/>"
                + "<learningecu ids=\"CD01A04007\" flkc=\"0xF813D2\" flkcrows=\"4\"/>"
                + "</learning></protocol></protocols></logger>");
        w.close();
        return f;
    }

    @Test
    public void picksTheEcusEntryInTheSubudsSection() throws Exception {
        final SUBUDSLearningDefinition d = SUBUDSLearningDefinition.load(definition(), "8B145A7A07");
        assertEquals("0xF81414", d.address("flkc"));
        assertEquals("0xF81466", d.address("aflearning"));
        assertNull(d.address("intake1"));
        assertEquals(7, d.getInt("flkcrows", 0));        // the SUBUDS block, not the SSM one
        assertEquals("x/256*0.3515625", d.get("flkcexpr", null));
    }

    @Test
    public void perEcuSettingsOverrideTheCommonOnes() throws Exception {
        final SUBUDSLearningDefinition d = SUBUDSLearningDefinition.load(definition(), "CD01A04007");
        assertEquals("0xF813D2", d.address("flkc"));
        assertEquals(4, d.getInt("flkcrows", 0));
    }

    @Test
    public void unknownEcuHasNoEntry() throws Exception {
        assertNull(SUBUDSLearningDefinition.load(definition(), "AAAAAAAAAA"));   // only in the SSM section
        assertNull(SUBUDSLearningDefinition.load(definition(), "0000000000"));
    }

    private static SUBUDSLearningTableValues.Axes axes(final Map<String, Double> v) {
        return new SUBUDSLearningTableValues.Axes() {
            public Double value(String name) { return v.get(name); }
            public String source() { return "test"; }
        };
    }

    @Test
    public void rangesFromTheRomBoundaries() {
        final Map<String, Double> v = new HashMap<String, Double>();
        v.put("RPM 1", 2400.0);
        v.put("RPM 2", 4400.0);
        assertArrayEquals(new String[]{"0 - 2399", "2400 - 4399", "4400+"},
                SUBUDSLearningTableValues.rowNames(axes(v), "RPM %d", 3, "%.0f", 1, "RPM range"));
        v.put("Load", 1.62);
        assertArrayEquals(new String[]{" ", "0.00 - 1.61", "1.62+"},
                SUBUDSLearningTableValues.columnNames(axes(v), "Load", 2, "%.2f", 0.01, "Load range"));
    }

    @Test
    public void numberedWhenABoundaryIsMissing() {
        final Map<String, Double> v = new HashMap<String, Double>();
        v.put("RPM 1", 2400.0);                                 // RPM 2 missing
        assertArrayEquals(new String[]{"RPM range 1", "RPM range 2", "RPM range 3"},
                SUBUDSLearningTableValues.rowNames(axes(v), "RPM %d", 3, "%.0f", 1, "RPM range"));
        assertArrayEquals(new String[]{" ", "Load range 1", "Load range 2"},
                SUBUDSLearningTableValues.columnNames(axes(v), null, 2, "%.2f", 0.01, "Load range"));
    }

    @Test
    public void blockQueriesStepByTheStorageSize() {
        final List<EcuQuery> all = new ArrayList<EcuQuery>();
        final EcuQuery[] q = SUBUDSLearningTableValues.block(all, "FLKC", "0xF81414", 35, "int16",
                "x/256*0.3515625", "deg", "0.00", Settings.Endian.BIG);
        assertEquals(35, all.size());
        assertEquals("F81414", q[0].getAddresses()[0].replace("0x", ""));
        assertEquals("F81458", q[34].getAddresses()[0].replace("0x", ""));
        q[1].setResponse(new byte[]{(byte) 0xF9, 0x00});       // -1792 -> -7 counts -> -2.46 degrees
        assertEquals(-2.4609375, q[1].getResponse(), 1e-9);
    }
}
