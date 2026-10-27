package com.atakmap.android.otsadmin;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Parsing and wording for {@code GET /api/status}.
 *
 * The sample below is shaped exactly like the server's own response, field
 * for field, taken from the OpenTAKServer source read 2026-10-24.
 */
public class OtsStatusTest {

    private static final String SAMPLE = "{"
            + "\"online_euds\":3,"
            + "\"system_boot_time\":\"2026-10-01 08:00:00Z\","
            + "\"system_uptime\":1987200.0,"
            + "\"ots_start_time\":\"2026-10-20 09:32:00Z\","
            + "\"ots_uptime\":358320.0,"
            + "\"cpu_time\":{\"user\":1.0,\"system\":2.0,\"idle\":3.0},"
            + "\"cpu_percent\":2.5,"
            + "\"load_avg\":[0.52,0.41,0.38],"
            + "\"memory\":{\"total\":8232062976,\"available\":5100000000,"
            + "\"used\":2800000000,\"free\":1000000000,\"percent\":34.0},"
            + "\"disk_usage\":{\"total\":62725623808,\"used\":21474836480,"
            + "\"free\":41250787328,\"percent\":34.2},"
            + "\"ots_version\":\"1.4.0\","
            + "\"uname\":{\"system\":\"Linux\",\"node\":\"ots-prod\","
            + "\"release\":\"6.1.0-21-arm64\",\"version\":\"#1 SMP\","
            + "\"machine\":\"aarch64\"},"
            + "\"os_release\":{\"NAME\":\"Debian GNU/Linux\","
            + "\"PRETTY_NAME\":\"Debian GNU/Linux 12 (bookworm)\","
            + "\"VERSION\":\"12\",\"VERSION_CODENAME\":\"bookworm\"},"
            + "\"python_version\":\"3.11.2\"}";

    @Test
    public void parsesTheServerShape() {
        OtsStatus s = OtsStatus.parse(SAMPLE);
        assertNotNull(s);
        assertEquals("1.4.0", s.otsVersion);
        assertEquals(3, s.onlineEuds);
        assertEquals("ots-prod", s.hostname);
        assertEquals("aarch64", s.machine);
        assertEquals("3.11.2", s.pythonVersion);
        assertEquals(3, s.loadAverage.size());
        assertEquals(0.52, s.loadAverage.get(0), 0.001);
        assertEquals(8232062976L, s.memoryTotal);
        assertEquals(41250787328L, s.diskFree);
    }

    // ---- the os_release null trap ---------------------------------------

    @Test
    public void nullOsReleaseBecomesUnknownRatherThanTheWordNull() {
        // The server wraps freedesktop_os_release() in a bare except and
        // substitutes None for every field -- what a container or a
        // non-systemd host gives. Rendering that puts "null" in front of the
        // operator.
        String body = "{\"ots_version\":\"1.4.0\",\"ots_uptime\":100.0,"
                + "\"os_release\":{\"NAME\":null,\"PRETTY_NAME\":null,"
                + "\"VERSION\":null,\"VERSION_CODENAME\":null},"
                + "\"uname\":{\"node\":null,\"release\":null,\"machine\":null}}";
        OtsStatus s = OtsStatus.parse(body);
        assertNotNull(s);
        assertEquals("", s.osPrettyName);
        for (OtsStatus.Row r : s.rows()) {
            assertFalse("no row may render the word null: " + r.label + "=" + r.value,
                    r.value.toLowerCase().contains("null"));
        }
    }

    // ---- not-a-status bodies --------------------------------------------

    @Test
    public void rejectsBodiesThatAreNotStatus() {
        // Returning null rather than a zeroed object matters: a dashboard of
        // zeroes would look like a healthy idle server.
        assertNull(OtsStatus.parse(null));
        assertNull(OtsStatus.parse(""));
        assertNull(OtsStatus.parse("   "));
        assertNull(OtsStatus.parse("{\"success\":false,\"error\":\"nope\"}"));
        assertNull(OtsStatus.parse("{\"results\":[],\"total_pages\":1}"));
        assertNull(OtsStatus.parse("not json at all"));
    }

    @Test
    public void rejectsAnHtmlLoginPage() {
        // The reason OtsClient stopped following redirects: a 302 to the login
        // page, followed, arrives as HTTP 200 full of HTML.
        assertNull(OtsStatus.parse("<!DOCTYPE html><html><body>Sign in</body></html>"));
    }

    // ---- uptime formatting ----------------------------------------------

    @Test
    public void uptimeReadsNaturallyAtEveryScale() {
        assertEquals("45 s", OtsStatus.uptime(45));
        assertEquals("12 min", OtsStatus.uptime(12 * 60));
        assertEquals("4 h 12 min", OtsStatus.uptime(4 * 3600 + 12 * 60));
        assertEquals("23 d 0 h", OtsStatus.uptime(1987200));
        assertEquals("unknown", OtsStatus.uptime(0));
        assertEquals("unknown", OtsStatus.uptime(-5));
    }

    @Test
    public void uptimeDoesNotReportSixtyMinutes() {
        // Rounding per unit produced "60 min" for the minute before the hour
        // in WxHazards CacheAge, which a test caught there too.
        for (int s = 3500; s <= 3700; s++) {
            String out = OtsStatus.uptime(s);
            assertFalse("bad rollover at " + s + "s: " + out,
                    out.contains("60 min"));
        }
    }

    // ---- byte formatting -------------------------------------------------

    @Test
    public void bytesUseBinaryUnits() {
        // Binary, because psutil reports binary and so does every other tool
        // the operator will compare against.
        assertEquals("1.0 KiB", OtsStatus.bytes(1024));
        assertEquals("7.7 GiB", OtsStatus.bytes(8232062976L));
        assertEquals("512 B", OtsStatus.bytes(512));
        assertEquals("unknown", OtsStatus.bytes(0));
        assertEquals("unknown", OtsStatus.bytes(-1));
    }

    // ---- rows ------------------------------------------------------------

    @Test
    public void rowsCoverEveryFieldAndLabelCpuAsTheProcess() {
        OtsStatus s = OtsStatus.parse(SAMPLE);
        List<OtsStatus.Row> rows = s.rows();
        assertFalse(rows.isEmpty());
        StringBuilder all = new StringBuilder();
        for (OtsStatus.Row r : rows)
            all.append(r.label).append('=').append(r.value).append('\n');
        String text = all.toString();

        assertTrue(text.contains("OTS version=1.4.0"));
        assertTrue(text.contains("Online EUDs=3"));
        assertTrue(text.contains("Debian GNU/Linux 12"));
        assertTrue(text.contains("aarch64"));
        assertTrue(text.contains("0.52, 0.41, 0.38"));
        // The CPU figure is OTS's process, not the machine; mislabelling it
        // would let a low number read as an idle server.
        assertTrue("CPU row must say it is the process",
                text.contains("OTS process CPU"));
        assertTrue(text.contains("7.7 GiB"));
    }

    @Test
    public void missingSectionsDegradeToUnknownNotZero() {
        OtsStatus s = OtsStatus.parse("{\"ots_version\":\"1.4.0\",\"ots_uptime\":10.0}");
        assertNotNull(s);
        boolean sawUnknownMemory = false;
        for (OtsStatus.Row r : s.rows()) {
            if ("Memory".equals(r.label))
                sawUnknownMemory = "unknown".equals(r.value);
        }
        assertTrue("absent memory must read unknown, not 0 B", sawUnknownMemory);
    }

    @Test
    public void attentionOnlyOnGenuinelyTightResources() {
        assertFalse(OtsStatus.parse(SAMPLE).wantsAttention());
        String tight = SAMPLE.replace("\"percent\":34.2", "\"percent\":93.0");
        assertTrue(OtsStatus.parse(tight).wantsAttention());
    }
}
