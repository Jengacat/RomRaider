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

import static com.romraider.logger.ecu.comms.io.connection.LoggerConnectionFactory.getConnection;
import static javax.swing.JOptionPane.ERROR_MESSAGE;
import static javax.swing.JOptionPane.showMessageDialog;

import java.io.File;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;

import javax.swing.SwingWorker;

import org.apache.log4j.Logger;

import com.romraider.Settings;
import com.romraider.editor.ecu.ECUEditor;
import com.romraider.editor.ecu.ECUEditorManager;
import com.romraider.logger.ecu.EcuLogger;
import com.romraider.logger.ecu.comms.io.connection.LoggerConnection;
import com.romraider.logger.ecu.comms.manager.PollingStateImpl;
import com.romraider.logger.ecu.comms.query.EcuQuery;
import com.romraider.logger.ecu.comms.query.EcuQueryImpl;
import com.romraider.logger.ecu.definition.EcuAddressImpl;
import com.romraider.logger.ecu.definition.EcuDataConvertor;
import com.romraider.logger.ecu.definition.EcuDefinition;
import com.romraider.logger.ecu.definition.EcuParameterConvertorImpl;
import com.romraider.logger.ecu.definition.EcuParameterImpl;
import com.romraider.logger.ecu.definition.Module;
import com.romraider.logger.ecu.definition.xml.ConverterMaxMinDefaults;
import com.romraider.logger.ecu.ui.swing.tools.SSMLearningTableValuesResultsPanel;
import com.romraider.logger.ecu.ui.paramlist.ParameterListTableModel;
import com.romraider.maps.DataCell;
import com.romraider.maps.Rom;
import com.romraider.maps.Table;
import com.romraider.util.ResourceUtil;

/**
 * Learning Table Values over SUBUDS: the learned values are read straight from
 * RAM with the logging requests (2C define by address, 22 read), at the
 * addresses the logger definition's &lt;learning&gt; block gives for this ECU
 * ({@link SUBUDSLearningDefinition}): fine learning knock correction table,
 * A/F learning table, DAM and the VVT learned rest positions. The table axes
 * come from the ROM definition's boundary scalars when the matching ROM is open
 * in the editor (they live in flash, which UDS only reads with security
 * access); otherwise the rows and columns are numbered.
 */
public final class SUBUDSLearningTableValues extends SwingWorker<Void, Void>
        implements LearningTableValues {

    private static final Logger LOGGER = Logger.getLogger(SUBUDSLearningTableValues.class);
    private static final ResourceBundle rb = new ResourceUtil().getBundle(
            SUBUDSLearningTableValues.class.getName());
    private EcuLogger logger;
    private Settings settings;
    private EcuDefinition ecuDef;

    /** What was read, ready for the results window. */
    public static final class Result {
        public final Map<String, Object> vehicleInfo = new LinkedHashMap<String, Object>();
        public String[] afRanges = new String[0];
        public final List<List<Object>> afLearning = new ArrayList<List<Object>>();
        public String[] flkcLoad = new String[0];
        public String[] flkcRpm = new String[0];
        public final List<List<EcuQuery>> flkc = new ArrayList<List<EcuQuery>>();
        public String axisSource = "";
    }

    /** Where the boundary values come from: an open ROM, or nowhere. */
    public interface Axes {
        /** Real value of the named scalar, or null. */
        Double value(String tableName);
        String source();
    }

    public SUBUDSLearningTableValues() {
    }

    @Override
    public void init(EcuLogger logger, ParameterListTableModel dataTabParamListTableModel, EcuDefinition ecuDef) {
        this.logger = logger;
        this.settings = logger.getSettings();
        this.ecuDef = ecuDef;
    }

    @Override
    protected Void doInBackground() {
        final boolean logging = logger.isLogging();
        try {
            final SUBUDSLearningDefinition def = SUBUDSLearningDefinition.load(
                    new File(settings.getLoggerDefinitionFilePath()), ecuDef.getEcuId());
            if (def == null) {
                showMessageDialog(logger, MessageFormat.format(rb.getString("NODEF"), ecuDef.getEcuId()),
                        rb.getString("LTV"), ERROR_MESSAGE);
                return null;
            }
            if (logging) logger.stopLogging();
            logger.reportMessage(rb.getString("READING"));
            final LoggerConnection connection = getConnection(settings.getLoggerProtocol(),
                    settings.getLoggerPort(), settings.getLoggerConnectionProperties());
            final Result result;
            try {
                result = read(connection, settings.getDestinationTarget(), def, ecuDef, openRomAxes(ecuDef));
            }
            finally {
                connection.close();
            }
            logger.reportMessage(rb.getString("DONE"));
            new SSMLearningTableValuesResultsPanel(logger, result.vehicleInfo, result.afRanges, result.afLearning,
                    result.flkcLoad, result.flkcRpm, result.flkc,
                    rb.getString("AFRANGES"), rb.getString("AFROWS")).displayLearningResultsPanel();
        }
        catch (Exception e) {
            logger.reportError(rb.getString("ERROR"));
            LOGGER.error("Learning values over SUBUDS", e);
            showMessageDialog(logger, MessageFormat.format(rb.getString("ERRORMSG"), e.getMessage()),
                    rb.getString("LTV"), ERROR_MESSAGE);
        }
        finally {
            if (logging) logger.startLogging();
        }
        return null;
    }

    /** Read every learned value in one go and lay it out for the window. */
    public static Result read(LoggerConnection connection, Module module, SUBUDSLearningDefinition def,
            EcuDefinition ecuDef, Axes axes) {
        final Settings.Endian endian = "little".equalsIgnoreCase(def.get("endian", "big"))
                ? Settings.Endian.LITTLE : Settings.Endian.BIG;
        final List<EcuQuery> all = new ArrayList<EcuQuery>();

        final int fr = def.getInt("flkcrows", 7), fc = def.getInt("flkccols", 5);
        final String ftype = def.get("flkctype", "int16");
        final EcuQuery[] flkc = def.address("flkc") == null ? null
                : block(all, "FLKC", def.address("flkc"), fr * fc, ftype, def.get("flkcexpr", "x"), "°", "0.00", endian);
        final int ar = def.getInt("afrows", 2), ac = def.getInt("afcols", 5);
        final EcuQuery[] af = def.address("aflearning") == null ? null
                : block(all, "AFL", def.address("aflearning"), ar * ac, def.get("aftype", "int8"),
                        def.get("afexpr", "x"), "%", "0.00", endian);
        final EcuQuery dam = def.address("dam") == null ? null
                : one(all, "DAM", def.address("dam"), def.get("damtype", "uint8"), def.get("damexpr", "x"), "DAM", "0.000", endian);
        final String[] vvtKeys = {"intake1", "intake2", "exhaust1", "exhaust2"};
        final EcuQuery[] vvt = new EcuQuery[4];
        for (int i = 0; i < 4; i++) {
            if (def.address(vvtKeys[i]) != null) {
                vvt[i] = one(all, vvtKeys[i], def.address(vvtKeys[i]), def.get("vvttype", "uint16"),
                        def.get("vvtexpr", "x"), "°", "0.00", endian);
            }
        }
        if (all.isEmpty()) throw new IllegalArgumentException("no learning addresses for this ECU");
        connection.sendAddressReads(all, module, new PollingStateImpl());

        final Result r = new Result();
        r.axisSource = axes.source();
        r.vehicleInfo.put("CAL ID", ecuDef.getCalId());
        r.vehicleInfo.put("ECU ID", ecuDef.getEcuId());
        r.vehicleInfo.put(rb.getString("CAR"), ecuDef.getCarString().replaceAll("Subaru ", ""));
        if (dam != null) r.vehicleInfo.put("DAM", String.format("%.3f", dam.getResponse()));
        final String[] vvtNames = {rb.getString("INTAKE1"), rb.getString("INTAKE2"),
                                   rb.getString("EXHAUST1"), rb.getString("EXHAUST2")};
        for (int i = 0; i < 4; i++) {
            if (vvt[i] != null) r.vehicleInfo.put(vvtNames[i], String.format("%.2f°", vvt[i].getResponse()));
        }
        r.vehicleInfo.put(rb.getString("AXES"), r.axisSource);

        if (flkc != null) {
            r.flkcRpm = rowNames(axes, def.get("flkcrowaxis", null), fr, "%.0f", 1, rb.getString("RPMBAND"));
            r.flkcLoad = columnNames(axes, def.get("flkccolaxis", null), fc, "%.2f", 0.01, rb.getString("LOADBAND"));
            for (int row = 0; row < fr; row++) {
                final List<EcuQuery> cells = new ArrayList<EcuQuery>();
                cells.add(null);                                   // column 0 holds the row name
                for (int col = 0; col < fc; col++) cells.add(flkc[row * fc + col]);
                r.flkc.add(cells);
            }
        }
        if (af != null) {
            r.afRanges = columnNames(axes, def.get("afcolaxis", null), ac, "%.2f", 0.01, rb.getString("LOADBAND"));
            final String[] rows = rowNames(axes, def.get("afrowaxis", null), ar, "%.0f", 1, rb.getString("RPMBAND"));
            for (int row = 0; row < ar; row++) {
                final List<Object> cells = new ArrayList<Object>();
                cells.add(rows[row]);
                for (int col = 0; col < ac; col++) {
                    cells.add(String.format("%.2f %%", af[row * ac + col].getResponse()));
                }
                r.afLearning.add(cells);
            }
        }
        return r;
    }

    /** n consecutive values of the storage type's size starting at address. */
    static EcuQuery[] block(List<EcuQuery> all, String id, String address, int n, String type, String expr,
            String units, String format, Settings.Endian endian) {
        final int size = type.contains("16") ? 2 : type.contains("32") ? 4 : 1;
        final long start = Long.parseLong(address.replace("0x", ""), 16);
        final EcuQuery[] q = new EcuQuery[n];
        for (int i = 0; i < n; i++) {
            q[i] = one(all, id + (i + 1), String.format("0x%0" + (address.length() - 2) + "X", start + (long) i * size),
                    type, expr, units, format, endian);
        }
        return q;
    }

    static EcuQuery one(List<EcuQuery> all, String id, String address, String type, String expr,
            String units, String format, Settings.Endian endian) {
        final int size = type.contains("16") ? 2 : type.contains("32") ? 4 : 1;
        final EcuDataConvertor convertor = new EcuParameterConvertorImpl(units, expr, format, -1, type, endian,
                new HashMap<String, String>(), ConverterMaxMinDefaults.getDefault());
        final EcuQuery q = new EcuQueryImpl(new EcuParameterImpl(id, id, id,
                new EcuAddressImpl(address, size, -1), null, null, null, new EcuDataConvertor[]{convertor}));
        all.add(q);
        return q;
    }

    /** "0 - b1-step", "b1 - b2-step", ..., "bn+"; numbered bands when the boundaries are unknown. */
    static String[] ranges(Axes axes, String pattern, int n, String fmt, double step, String band) {
        final String[] out = new String[n];
        final double[] b = new double[n - 1];
        boolean known = pattern != null;
        for (int i = 0; known && i < n - 1; i++) {
            final Double v = axes.value(pattern.contains("%d") ? String.format(pattern, i + 1) : pattern);
            if (v == null) known = false;
            else b[i] = v;
        }
        for (int i = 0; i < n; i++) {
            if (!known) out[i] = band + " " + (i + 1);
            else if (i == n - 1) out[i] = String.format(fmt + "+", b[i - 1]);
            else out[i] = String.format(fmt + " - " + fmt, i == 0 ? 0 : b[i - 1], b[i] - step);
        }
        return out;
    }

    static String[] rowNames(Axes axes, String pattern, int n, String fmt, double step, String band) {
        return ranges(axes, pattern, n, fmt, step, band);
    }

    /** Column headings: the results window's first column is the row name, so a blank first. */
    static String[] columnNames(Axes axes, String pattern, int n, String fmt, double step, String band) {
        final String[] r = ranges(axes, pattern, n, fmt, step, band);
        final String[] out = new String[n + 1];
        out[0] = " ";
        System.arraycopy(r, 0, out, 1, n);
        return out;
    }

    /** Boundary scalars from the editor's ROM with this CAL ID, if one is open. */
    static Axes openRomAxes(EcuDefinition ecuDef) {
        Rom found = null;
        try {
            final ECUEditor editor = ECUEditorManager.getECUEditorWithoutCreation();
            if (editor != null) {
                for (Rom rom : editor.getImages()) {
                    if (rom.getRomID() != null && rom.getRomIDString() != null
                            && rom.getRomIDString().equalsIgnoreCase(ecuDef.getCalId())) {
                        found = rom;
                        break;
                    }
                }
            }
        }
        catch (Exception e) {
            LOGGER.debug("No open ROM for the learning view axes", e);
        }
        final Rom rom = found;
        return new Axes() {
            public Double value(String tableName) {
                if (rom == null) return null;
                try {
                    final Table t = rom.getTableByName(tableName);
                    if (t == null) return null;
                    final DataCell[] d = t.getData();
                    return d == null || d.length == 0 ? null : d[0].getRealValue();
                }
                catch (Exception e) {
                    return null;
                }
            }

            public String source() {
                return rom == null ? rb.getString("AXESNONE") : MessageFormat.format(rb.getString("AXESROM"),
                        rom.getRomIDString());
            }
        };
    }
}
