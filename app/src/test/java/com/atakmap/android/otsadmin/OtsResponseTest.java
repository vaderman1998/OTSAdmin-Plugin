package com.atakmap.android.otsadmin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/** Response shapes taken from the server's paginate() helper and blueprints. */
public class OtsResponseTest {

    /** Exactly what paginate() builds. */
    private static final String PAGE = "{\"results\":["
            + "{\"username\":\"alice\"},{\"username\":\"bob\"}],"
            + "\"total_pages\":3,\"current_page\":2,\"per_page\":2,"
            + "\"total\":5}";

    /** What /api/login returns with include_auth_token. */
    private static final String LOGIN = "{\"meta\":{\"code\":200},"
            + "\"response\":{\"csrf_token\":\"CSRF-NOT-THIS-ONE\","
            + "\"user\":{\"authentication_token\":\"TOKEN-THIS-ONE\"}}}";

    @Test
    public void aPaginatedListReadsAsOnePage() throws Exception {
        OtsResponse.Page p = OtsResponse.page(PAGE);
        assertEquals(2, p.results.size());
        assertEquals("alice", p.results.get(0).optString("username"));
        assertEquals(3, p.totalPages);
        assertEquals(2, p.currentPage);
        assertEquals(5, p.total);
        assertTrue(p.hasMore());
        assertFalse(p.isEmpty());
    }

    @Test
    public void theLastPageKnowsItIsTheLast() throws Exception {
        OtsResponse.Page p = OtsResponse.page(
                "{\"results\":[],\"total_pages\":3,\"current_page\":3,"
                        + "\"per_page\":2,\"total\":5}");
        assertFalse(p.hasMore());
        assertTrue(p.isEmpty());
    }

    @Test
    public void aBareArrayIsAlsoAccepted() throws Exception {
        // /api/eud?all=1 and /api/users/all return a bare JSON array with no
        // envelope. Code expecting "results" finds nothing there and reports
        // an empty server, which is indistinguishable from a working one
        // with nothing on it.
        OtsResponse.Page p = OtsResponse.page(
                "[{\"uid\":\"u-1\",\"callsign\":\"ALPHA\"},"
                        + "{\"uid\":\"u-2\",\"callsign\":\"BRAVO\"}]");
        assertEquals(2, p.results.size());
        assertEquals("ALPHA", p.results.get(0).optString("callsign"));
        assertEquals(2, p.total);
        assertFalse(p.hasMore());
    }

    @Test
    public void anEmptyListIsNotAnError() throws Exception {
        assertTrue(OtsResponse.page("{\"results\":[]}").isEmpty());
        assertTrue(OtsResponse.page("[]").isEmpty());
        assertTrue(OtsResponse.page("{}").isEmpty());
        // A page with no counters still reads as one page.
        assertEquals(1, OtsResponse.page("{\"results\":[]}").totalPages);
    }

    @Test
    public void junkRowsInsideAListAreSkippedNotFatal() throws Exception {
        OtsResponse.Page p = OtsResponse.page(
                "{\"results\":[{\"username\":\"alice\"},null,\"nonsense\","
                        + "{\"username\":\"bob\"}]}");
        assertEquals(2, p.results.size());
    }

    // ------------------------------------------------------------------
    // Login
    // ------------------------------------------------------------------

    @Test
    public void theAuthTokenIsTakenFromTheRightNestedField() {
        // response.user.authentication_token - NOT response.csrf_token,
        // which sits one level up and is the wrong one. Sending the CSRF
        // token authenticates as nobody and produces 401s that look exactly
        // like a wrong password.
        assertEquals("TOKEN-THIS-ONE", OtsResponse.authToken(LOGIN));
    }

    @Test
    public void amissingTokenIsNullRatherThanEmpty() {
        assertNull(OtsResponse.authToken("{}"));
        assertNull(OtsResponse.authToken("{\"response\":{}}"));
        assertNull(OtsResponse.authToken(
                "{\"response\":{\"user\":{}}}"));
        assertNull(OtsResponse.authToken("not json at all"));
        assertNull(OtsResponse.authToken(null));
        // A login that returned only a CSRF token must not be mistaken for
        // a success.
        assertNull(OtsResponse.authToken(
                "{\"response\":{\"csrf_token\":\"x\"}}"));
    }

    // ------------------------------------------------------------------
    // Errors
    // ------------------------------------------------------------------

    @Test
    public void theServersOwnWordingIsWhatComesBack() {
        // Shown verbatim on purpose: "You can't delete your own account"
        // explains itself better than anything we would substitute.
        assertEquals("You can't delete your own account",
                OtsResponse.error("{\"success\":false,\"error\":"
                        + "\"You can't delete your own account\"}"));
        assertEquals("The __ANON__ group cannot be deleted",
                OtsResponse.error("{\"success\":false,\"error\":"
                        + "\"The __ANON__ group cannot be deleted\"}"));
    }

    @Test
    public void aSuccessIsNotReadAsAnError() {
        assertNull(OtsResponse.error("{\"success\":true}"));
        assertTrue(OtsResponse.succeeded("{\"success\":true}"));
        assertFalse(OtsResponse.succeeded("{\"success\":false}"));
        assertFalse(OtsResponse.succeeded("{}"));
        assertFalse(OtsResponse.succeeded(null));
    }

    @Test
    public void aFieldCalledErrorOnASuccessIsNotAFailure() {
        // A scheduler job carries its last error. Treating the presence of
        // the key as the signal would report every job listing as broken.
        assertNull(OtsResponse.error(
                "{\"results\":[{\"id\":\"j1\",\"error\":\"last run failed\"}]}"));
    }

    @Test
    public void aFlaskSecurityLoginFailureIsReadable() {
        // Flask-Security does not use the success/error envelope; it puts
        // the reason under response.field_errors with meta.code 400.
        String body = "{\"meta\":{\"code\":400},\"response\":{"
                + "\"field_errors\":{\"password\":"
                + "[\"Invalid password\"]}}}";
        assertEquals("Invalid password", OtsResponse.error(body));
    }

    @Test
    public void aCodedFailureWithNoDetailStillSaysSomething() {
        assertEquals("the server returned 500",
                OtsResponse.error("{\"meta\":{\"code\":500}}"));
        assertEquals("the server refused it, with no reason given",
                OtsResponse.error("{\"success\":false}"));
    }

    @Test
    public void unreadableBodiesAreNotErrorsTheyAreNothing() {
        // So an HTML error page from a reverse proxy does not become a
        // confident but meaningless message.
        assertNull(OtsResponse.error("<html>502 Bad Gateway</html>"));
        assertNull(OtsResponse.error(null));
        assertNull(OtsResponse.error(""));
    }

    // ------------------------------------------------------------------
    // Roles
    // ------------------------------------------------------------------

    @Test
    public void rolesAreReadAsStringsOrAsObjects() {
        assertEquals(Arrays.asList("administrator", "user"),
                OtsResponse.roles(
                        "{\"roles\":[\"administrator\",\"user\"]}"));
        assertEquals(Collections.singletonList("administrator"),
                OtsResponse.roles(
                        "{\"roles\":[{\"name\":\"administrator\"}]}"));
        assertEquals(Collections.singletonList("user"),
                OtsResponse.roles(
                        "{\"response\":{\"roles\":[\"user\"]}}"));
    }

    @Test
    public void unknownRolesMeanNotAnAdministrator() {
        // The safe direction: an unreadable /api/me must not be treated as
        // permission to offer destructive controls.
        assertFalse(OtsResponse.isAdministrator(OtsResponse.roles("{}")));
        assertFalse(OtsResponse.isAdministrator(
                OtsResponse.roles("garbage")));
        assertFalse(OtsResponse.isAdministrator(null));
        assertFalse(OtsResponse.isAdministrator(
                Collections.singletonList("user")));
        assertTrue(OtsResponse.isAdministrator(
                Arrays.asList("user", "administrator")));
        // Case and padding as the server might send them.
        assertTrue(OtsResponse.isAdministrator(
                Collections.singletonList(" Administrator ")));
    }

    // ------------------------------------------------------------------
    // Redaction
    // ------------------------------------------------------------------

    @Test
    public void aLoggedRequestBodyNeverCarriesThePassword() {
        // The plugin logs what it sent when a call fails, and that line is
        // the most useful thing in a diagnosis - but logcat is readable
        // with a cable.
        String body = OtsRequest.addUser("alice", "hunter2", null).body;
        String safe = OtsJson.redact(body);
        assertFalse(safe, safe.contains("hunter2"));
        assertTrue(safe, safe.contains("alice"));
        assertTrue(safe, safe.contains("\"password\":\"***\""));
        assertTrue(safe, safe.contains("\"confirm_password\":\"***\""));
    }

    @Test
    public void redactionSurvivesAPasswordContainingQuotes() {
        String body = OtsRequest.login("admin", "a\"b\\c").body;
        String safe = OtsJson.redact(body);
        assertFalse(safe, safe.contains("a\\\"b"));
        assertTrue(safe, safe.contains("\"password\":\"***\""));
        assertNull(OtsJson.redact(null));
    }
}
