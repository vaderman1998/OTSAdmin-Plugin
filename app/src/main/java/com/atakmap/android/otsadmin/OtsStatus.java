package com.atakmap.android.otsadmin;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The server's own status, from {@code GET /api/status}.
 *
 * This is the data behind the web UI's Dashboard page: how long OTS and the
 * host have been up, what the machine is doing, and how many EUDs are
 * connected.
 *
 * <h3>What this is NOT</h3>
 * <b>It is not a list of services and it cannot start or restart anything.</b>
 * Read against the OpenTAKServer source 2026-10-24: across all twenty API
 * blueprints there is no {@code systemctl}, {@code systemd},
 * {@code subprocess} or {@code os.system} call anywhere, and the only
 * {@code psutil.Process()} is OTS reading its own CPU percent. So there is no
 * per-service up/down list for nginx, mosquitto, RabbitMQ or MediaMTX, and no
 * endpoint that would restart them — not in this plugin, and not in the web UI
 * either, because the route simply does not exist. Anything claiming otherwise
 * would be a button that lies.
 *
 * <h3>Traps, both read straight out of the server source</h3>
 * <ul>
 *   <li><b>{@code os_release} values can be JSON null.</b> The server wraps
 *       {@code platform.freedesktop_os_release()} in a bare {@code except} and
 *       substitutes {@code None} for every field, which is what a non-systemd
 *       or container host gives. Rendering that lands "null" in front of the
 *       operator, so it becomes "unknown" here.</li>
 *   <li><b>{@code cpu_percent} is OTS's own process</b>, not the machine, and
 *       psutil's first reading after a call gap can be 0.0. It is labelled as
 *       the process so a low number is not read as an idle server; the machine
 *       figure to look at is the load average.</li>
 * </ul>
 *
 * Plain Java (org.json only) so the parsing and the formatting are testable
 * off device.
 */
public final class OtsStatus {

    public final String otsVersion;
    public final double otsUptimeSeconds;
    public final double systemUptimeSeconds;
    public final String otsStartTime;
    public final String systemBootTime;

    /** OTS's own process CPU percent, not the machine's. */
    public final double processCpuPercent;
    /** 1, 5 and 15 minute load averages; empty if absent. */
    public final List<Double> loadAverage;

    public final long memoryTotal;
    public final long memoryUsed;
    public final long memoryAvailable;
    public final double memoryPercent;

    public final long diskTotal;
    public final long diskUsed;
    public final long diskFree;
    public final double diskPercent;

    public final int onlineEuds;

    public final String osPrettyName;
    public final String kernelRelease;
    public final String hostname;
    public final String machine;
    public final String pythonVersion;

    private OtsStatus(JSONObject o) {
        otsVersion = text(o, "ots_version");
        otsUptimeSeconds = o.optDouble("ots_uptime", 0);
        systemUptimeSeconds = o.optDouble("system_uptime", 0);
        otsStartTime = text(o, "ots_start_time");
        systemBootTime = text(o, "system_boot_time");

        processCpuPercent = o.optDouble("cpu_percent", 0);

        List<Double> load = new ArrayList<>();
        JSONArray la = o.optJSONArray("load_avg");
        if (la != null) {
            for (int i = 0; i < la.length(); i++)
                load.add(la.optDouble(i, 0));
        }
        loadAverage = load;

        JSONObject mem = o.optJSONObject("memory");
        memoryTotal = mem == null ? 0 : mem.optLong("total", 0);
        memoryUsed = mem == null ? 0 : mem.optLong("used", 0);
        memoryAvailable = mem == null ? 0 : mem.optLong("available", 0);
        memoryPercent = mem == null ? 0 : mem.optDouble("percent", 0);

        JSONObject disk = o.optJSONObject("disk_usage");
        diskTotal = disk == null ? 0 : disk.optLong("total", 0);
        diskUsed = disk == null ? 0 : disk.optLong("used", 0);
        diskFree = disk == null ? 0 : disk.optLong("free", 0);
        diskPercent = disk == null ? 0 : disk.optDouble("percent", 0);

        onlineEuds = o.optInt("online_euds", 0);

        JSONObject os = o.optJSONObject("os_release");
        osPrettyName = os == null ? "" : text(os, "PRETTY_NAME");
        JSONObject un = o.optJSONObject("uname");
        kernelRelease = un == null ? "" : text(un, "release");
        hostname = un == null ? "" : text(un, "node");
        machine = un == null ? "" : text(un, "machine");
        pythonVersion = text(o, "python_version");
    }

    /**
     * Parse a status body, or null if it is not one.
     *
     * Returns null rather than an empty object so a caller cannot show a
     * dashboard of zeroes and call it a healthy server.
     */
    public static OtsStatus parse(String json) {
        if (json == null || json.trim().isEmpty())
            return null;
        try {
            JSONObject o = new JSONObject(json);
            if (o.has("error"))
                return null;
            // ots_version is the one field that identifies this as a status
            // body; a login page or an unrelated envelope has none of it.
            if (!o.has("ots_version") && !o.has("ots_uptime"))
                return null;
            return new OtsStatus(o);
        } catch (JSONException e) {
            return null;
        }
    }

    /** JSON null and the string "null" both become "". */
    private static String text(JSONObject o, String key) {
        if (o.isNull(key))
            return "";
        String v = o.optString(key, "");
        return "null".equals(v) ? "" : v;
    }

    // ------------------------------------------------------------------
    // Formatting
    // ------------------------------------------------------------------

    /** "3 d 4 h", "4 h 12 min", "12 min", "45 s". */
    public static String uptime(double seconds) {
        if (seconds <= 0)
            return "unknown";
        long total = (long) seconds;
        long days = total / 86400;
        long hours = (total % 86400) / 3600;
        long mins = (total % 3600) / 60;
        if (days > 0)
            return days + " d " + hours + " h";
        if (hours > 0)
            return hours + " h " + mins + " min";
        if (mins > 0)
            return mins + " min";
        return total + " s";
    }

    /**
     * Binary units, because that is what psutil reports and what every other
     * tool on the host will agree with.
     */
    public static String bytes(long value) {
        if (value <= 0)
            return "unknown";
        double v = value;
        String[] units = { "B", "KiB", "MiB", "GiB", "TiB" };
        int i = 0;
        while (v >= 1024 && i < units.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format(Locale.US, v < 10 && i > 0 ? "%.1f %s" : "%.0f %s",
                v, units[i]);
    }

    /** One label/value row for display. */
    public static final class Row {
        public final String label;
        public final String value;

        Row(String label, String value) {
            this.label = label;
            this.value = value;
        }
    }

    /**
     * The status as display rows, so the pane holds no formatting logic and
     * the wording is covered by tests.
     */
    public List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("OTS version", or(otsVersion, "unknown")));
        rows.add(new Row("OTS uptime", uptime(otsUptimeSeconds)));
        rows.add(new Row("OTS started", or(otsStartTime, "unknown")));
        rows.add(new Row("Online EUDs", String.valueOf(onlineEuds)));
        rows.add(new Row("Host", or(hostname, "unknown")));
        rows.add(new Row("OS", or(osPrettyName, "unknown")));
        rows.add(new Row("Kernel", or(kernelRelease, "unknown")
                + (machine.isEmpty() ? "" : " (" + machine + ")")));
        rows.add(new Row("System uptime", uptime(systemUptimeSeconds)));
        rows.add(new Row("Load average", loadAverage.isEmpty()
                ? "unknown" : formatLoad()));
        // Labelled as the process, not the machine -- see the class javadoc.
        rows.add(new Row("OTS process CPU",
                String.format(Locale.US, "%.1f%%", processCpuPercent)));
        rows.add(new Row("Memory", memoryTotal == 0 ? "unknown"
                : String.format(Locale.US, "%s of %s used (%.0f%%)",
                        bytes(memoryUsed), bytes(memoryTotal), memoryPercent)));
        rows.add(new Row("Disk", diskTotal == 0 ? "unknown"
                : String.format(Locale.US, "%s of %s used (%.0f%%), %s free",
                        bytes(diskUsed), bytes(diskTotal), diskPercent,
                        bytes(diskFree))));
        rows.add(new Row("Python", or(pythonVersion, "unknown")));
        return rows;
    }

    private String formatLoad() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < loadAverage.size(); i++) {
            if (i > 0)
                sb.append(", ");
            sb.append(String.format(Locale.US, "%.2f", loadAverage.get(i)));
        }
        return sb.toString();
    }

    private static String or(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    /**
     * True if the figures suggest the operator should look closer.
     *
     * Deliberately conservative: this is a hint for a colour, not an alarm,
     * and a server legitimately runs hot.
     */
    public boolean wantsAttention() {
        return diskPercent >= 90 || memoryPercent >= 95;
    }
}
