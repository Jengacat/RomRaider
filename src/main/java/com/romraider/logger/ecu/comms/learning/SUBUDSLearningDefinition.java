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

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

/**
 * The &lt;learning&gt; block of a logger definition's SUBUDS protocol: where
 * one ECU keeps its learned values in RAM and how to show them.
 * <pre>
 * &lt;protocol id="SUBUDS"&gt; ...
 *  &lt;learning flkcrows="7" flkccols="5" flkctype="int16" flkcexpr="x/256*0.3515625"
 *            afrows="2" afcols="5" aftype="int8" afexpr="x*0.390625"
 *            vvttype="uint16" vvtexpr="x/364.0889" damtype="uint8" damexpr="x/16" endian="big"
 *            flkcrowaxis="FLKC Range Boundary RPM %d" flkccolaxis="FLKC Range Boundary Load %d"
 *            afrowaxis="LTFT RPM Range Boundary" afcolaxis="LTFT Load Range Boundary %d"&gt;
 *   &lt;learningecu ids="A529A02007,..." flkc="0xF81414" aflearning="0xF81466" dam="0xF811AE"
 *                intake1="0xF81582" intake2="0xF81580" exhaust1="0xF8157E" exhaust2="0xF8157C"/&gt;
 *  &lt;/learning&gt;
 * </pre>
 * The tables are row-major (cell = cols x row + col). Any address may be left
 * out. The axis attributes name the ROM definition's scalars that hold the
 * row / column boundaries (%d = 1, 2, ...). The stock logger parser ignores
 * these elements.
 */
public final class SUBUDSLearningDefinition {
    final Map<String, String> common = new HashMap<String, String>();
    final Map<String, String> ecu = new HashMap<String, String>();

    private SUBUDSLearningDefinition() {
    }

    /** The learning block for one ECU ID, or null if the file has none for it. */
    public static SUBUDSLearningDefinition load(File loggerDefinition, final String ecuId) throws Exception {
        final SUBUDSLearningDefinition def = new SUBUDSLearningDefinition();
        final boolean[] found = {false};
        SAXParserFactory.newInstance().newSAXParser().parse(loggerDefinition, new DefaultHandler() {
            private boolean inSubuds;
            private boolean inLearning;

            @Override
            public void startElement(String uri, String localName, String qName, Attributes a) {
                if ("protocol".equals(qName)) {
                    inSubuds = "SUBUDS".equalsIgnoreCase(a.getValue("id"));
                }
                else if (inSubuds && "learning".equals(qName)) {
                    inLearning = true;
                    copy(a, def.common);
                }
                else if (inLearning && "learningecu".equals(qName) && !found[0]
                        && a.getValue("ids") != null
                        && Arrays.asList(a.getValue("ids").split(",")).contains(ecuId)) {
                    copy(a, def.ecu);
                    found[0] = true;
                }
            }

            @Override
            public void endElement(String uri, String localName, String qName) {
                if ("learning".equals(qName)) inLearning = false;
                if ("protocol".equals(qName)) inSubuds = false;
            }
        });
        return found[0] ? def : null;
    }

    private static void copy(Attributes a, Map<String, String> to) {
        for (int i = 0; i < a.getLength(); i++) {
            to.put(a.getQName(i), a.getValue(i));
        }
    }

    public String get(String key, String dflt) {
        final String v = ecu.containsKey(key) ? ecu.get(key) : common.get(key);
        return v != null ? v : dflt;
    }

    public int getInt(String key, int dflt) {
        return Integer.parseInt(get(key, String.valueOf(dflt)));
    }

    public String address(String key) {
        return ecu.get(key);
    }
}
