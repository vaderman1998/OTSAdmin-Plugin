package com.atakmap.android.otsadmin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/**
 * The writes added beyond users and groups, each asserted against the
 * contract in OpenTAKServer's blueprints (read 2026-10-10).
 */
public class OtsWritesTest {

    private static final String BASE = "https://tak.example.org";

    // ------------------------------------------------------------------
    // Users
    // ------------------------------------------------------------------

    @Test
    public void resettingAPasswordSendsUsernameAndNewPassword() {
        OtsRequest r = OtsRequest.resetPassword("alice", "correct-horse");
        assertEquals(OtsRequest.Method.POST, r.method);
        assertEquals(BASE + "/api/user/password/reset", r.url(BASE));
        assertEquals("{\"username\":\"alice\","
                + "\"new_password\":\"correct-horse\"}", r.body);
        // And it is redacted from any log line.
        assertFalse(OtsJson.redact(r.body).contains("correct-horse"));
    }

    @Test
    public void activateAndDeactivateAreSeparateEndpoints() {
        // Not one endpoint with a flag, which is what you would guess.
        assertEquals(BASE + "/api/user/activate",
                OtsRequest.activateUser("alice").url(BASE));
        assertEquals(BASE + "/api/user/deactivate",
                OtsRequest.deactivateUser("alice").url(BASE));
        for (OtsRequest r : new OtsRequest[] {
                OtsRequest.activateUser("alice"),
                OtsRequest.deactivateUser("alice") }) {
            assertEquals(OtsRequest.Method.POST, r.method);
            assertEquals("{\"username\":\"alice\"}", r.body);
        }
    }

    @Test
    public void rolesAreSentAsAListAndReplaceWhatWasThere() {
        OtsRequest r = OtsRequest.setUserRoles("alice",
                Arrays.asList("user", "administrator"));
        assertEquals(OtsRequest.Method.POST, r.method);
        assertEquals(BASE + "/api/user/role", r.url(BASE));
        assertEquals("{\"username\":\"alice\","
                + "\"roles\":[\"user\",\"administrator\"]}", r.body);
        // An empty list is still a list, not an omitted field - the guard is
        // what stops it being sent, not the builder.
        assertEquals("{\"username\":\"alice\",\"roles\":[]}",
                OtsRequest.setUserRoles("alice",
                        Collections.<String>emptyList()).body);
    }

    @Test
    public void theUserSideOfGroupsIsPluralAndTakesGroupsNotUsers() {
        // PUT /api/users/groups - note the plural "users", unlike every
        // other user endpoint, and that it carries a list of GROUPS for one
        // user: the mirror of addGroupMembers, which carries a list of users
        // for one group.
        OtsRequest r = OtsRequest.setUserGroups("alice",
                Arrays.asList("Bravo", "Charlie"), "IN");
        assertEquals(OtsRequest.Method.PUT, r.method);
        assertEquals(BASE + "/api/users/groups", r.url(BASE));
        assertEquals("{\"username\":\"alice\","
                + "\"groups\":[\"Bravo\",\"Charlie\"],"
                + "\"direction\":\"IN\"}", r.body);
    }

    @Test
    public void assigningAnEudTakesTheUidAndTheUsername() {
        OtsRequest r = OtsRequest.assignEud("alice", "ANDROID-abc123");
        assertEquals(BASE + "/api/user/assign_eud", r.url(BASE));
        assertEquals("{\"username\":\"alice\",\"uid\":\"ANDROID-abc123\"}",
                r.body);
    }

    // ------------------------------------------------------------------
    // Scheduler
    // ------------------------------------------------------------------

    @Test
    public void schedulerActionsAreThreePathsWithJobIdInTheBody() {
        assertEquals(BASE + "/api/scheduler/job/pause",
                OtsRequest.pauseJob("j1").url(BASE));
        assertEquals(BASE + "/api/scheduler/job/resume",
                OtsRequest.resumeJob("j1").url(BASE));
        assertEquals(BASE + "/api/scheduler/job/run",
                OtsRequest.runJob("j1").url(BASE));
        for (OtsRequest r : new OtsRequest[] { OtsRequest.pauseJob("j1"),
                OtsRequest.resumeJob("j1"), OtsRequest.runJob("j1") }) {
            assertEquals(OtsRequest.Method.POST, r.method);
            assertEquals("{\"job_id\":\"j1\"}", r.body);
        }
    }

    // ------------------------------------------------------------------
    // Server plugins
    // ------------------------------------------------------------------

    @Test
    public void serverPluginNameGoesInThePathWithNoBody() {
        OtsRequest r = OtsRequest.enableServerPlugin("ots_mumble_plugin");
        assertEquals(OtsRequest.Method.POST, r.method);
        assertEquals(BASE + "/api/plugins/ots_mumble_plugin/enable",
                r.url(BASE));
        assertFalse(r.hasBody());
        assertEquals(BASE + "/api/plugins/ots_mumble_plugin/disable",
                OtsRequest.disableServerPlugin("ots_mumble_plugin")
                        .url(BASE));
    }

    @Test
    public void aSlashInAPluginNameCannotEscapeIntoTheRoute() {
        // The name is interpolated into the path, so a raw slash would point
        // the request at a different route entirely.
        OtsRequest r = OtsRequest.enableServerPlugin("evil/../../api/login");
        assertFalse(r.url(BASE), r.url(BASE).contains("/../"));
        assertTrue(r.url(BASE), r.url(BASE).contains("%2F"));
        assertTrue(r.url(BASE).endsWith("/enable"));
    }

    // ------------------------------------------------------------------
    // Deletes keyed on query parameters
    // ------------------------------------------------------------------

    @Test
    public void aDataPackageIsDeletedByHashAsAQueryParam() {
        OtsRequest r = OtsRequest.deleteDataPackage("abc123");
        assertEquals(OtsRequest.Method.DELETE, r.method);
        assertEquals(BASE + "/api/data_packages?hash=abc123", r.url(BASE));
        assertFalse(r.hasBody());
    }

    @Test
    public void aDeviceProfileIsKeyedOnTheEudAndThePreference() {
        // Not a single id: a profile is a set of preferences, and this
        // removes one of them for one EUD.
        OtsRequest r = OtsRequest.deleteDeviceProfile("ANDROID-abc",
                "displayRed");
        assertEquals(OtsRequest.Method.DELETE, r.method);
        assertEquals(BASE + "/api/profiles?eud_uid=ANDROID-abc"
                + "&preference_key=displayRed", r.url(BASE));
    }

    // ------------------------------------------------------------------
    // Guards
    // ------------------------------------------------------------------

    @Test
    public void thePasswordRulesAreTheServersOwn() {
        // At least 8 characters, from SECURITY_PASSWORD_LENGTH_MIN.
        assertEquals(8, OtsWriteGuard.MIN_PASSWORD_LENGTH);
        assertNotNull(OtsWriteGuard.blockResetPassword("alice", "short",
                "short", false));
        assertNull(OtsWriteGuard.blockResetPassword("alice", "longenough",
                "longenough", false));
        // And NO colon or at-sign, which is the surprising one: a password
        // manager will suggest one containing either without hesitation.
        assertNotNull(OtsWriteGuard.blockResetPassword("alice",
                "has:a:colon", "has:a:colon", false));
        assertNotNull(OtsWriteGuard.blockResetPassword("alice",
                "has@an@at", "has@an@at", false));
        assertTrue(OtsWriteGuard.blockResetPassword("alice", "has@an@at",
                "has@an@at", false).contains("at-sign"));
        // Mismatch and blanks.
        assertNotNull(OtsWriteGuard.blockResetPassword("alice", "abcdefgh",
                "abcdefgi", false));
        assertNotNull(OtsWriteGuard.blockResetPassword("alice", "", "",
                false));
        assertNotNull(OtsWriteGuard.blockResetPassword("", "abcdefgh",
                "abcdefgh", false));
        // LDAP owns accounts.
        assertNotNull(OtsWriteGuard.blockResetPassword("alice", "abcdefgh",
                "abcdefgh", true));
    }

    @Test
    public void rolesCannotBeEmptiedAndAdminNeedsAdmin() {
        // Roles REPLACE, so sending none would leave an account with none.
        assertNotNull(OtsWriteGuard.blockSetRoles("alice",
                Collections.<String>emptyList(), true, false));
        assertNotNull(OtsWriteGuard.blockSetRoles("alice", null, true,
                false));
        assertNotNull(OtsWriteGuard.blockSetRoles("alice",
                Arrays.asList("administrator"), false, false));
        assertNull(OtsWriteGuard.blockSetRoles("alice",
                Arrays.asList("user"), false, false));
        assertNull(OtsWriteGuard.blockSetRoles("alice",
                Arrays.asList("user", "administrator"), true, false));
    }

    @Test
    public void deactivatingYourOwnAccountIsBlockedLocally() {
        // The server does NOT stop this - unlike deleting your own account,
        // which it refuses outright - so this rule is ours. Worth having:
        // the next request would fail and the reason would not be obvious.
        assertNotNull(OtsWriteGuard.blockDeactivate("admin", "admin",
                false));
        assertNotNull(OtsWriteGuard.blockDeactivate("ADMIN", "admin",
                false));
        assertNull(OtsWriteGuard.blockDeactivate("mallory", "admin",
                false));
        assertNotNull(OtsWriteGuard.blockDeactivate("mallory", "admin",
                true));
    }

    @Test
    public void everyGuardMessageIsWrittenForAPersonHoldingAPhone() {
        String[] messages = {
                OtsWriteGuard.blockResetPassword("alice", "x", "x", false),
                OtsWriteGuard.blockSetRoles("alice", null, true, false),
                OtsWriteGuard.blockDeactivate("admin", "admin", false) };
        for (String m : messages) {
            assertNotNull(m);
            assertTrue(m, m.endsWith("."));
            assertFalse(m, m.contains("null"));
        }
    }

    // ------------------------------------------------------------------
    // Which sections the plugin can now change
    // ------------------------------------------------------------------

    @Test
    public void theWritableSectionsAreTheOnesWithWriteEndpoints() {
        assertTrue(OtsSection.USERS.writable);
        assertTrue(OtsSection.GROUPS.writable);
        assertTrue(OtsSection.SCHEDULER.writable);
        assertTrue(OtsSection.PLUGINS.writable);
        assertTrue(OtsSection.DATA_PACKAGES.writable);
        assertTrue(OtsSection.PROFILES.writable);
        // Still read-only, and each for a reason: EUDs and certificates have
        // no delete, video stream add/update is driven by a WTForms config
        // taking arbitrary MediaMTX settings, and missions were not read.
        assertFalse(OtsSection.EUDS.writable);
        assertFalse(OtsSection.CERTIFICATES.writable);
        assertFalse(OtsSection.VIDEO.writable);
        assertFalse(OtsSection.MISSIONS.writable);
    }

    @Test
    public void aProfileRowShowsTheFieldsItsDeleteIsKeyedOn() {
        // Not cosmetic: a delete needs eud_uid and preference_key, so a row
        // that does not show them cannot be acted on.
        String[] fields = OtsSection.PROFILES.fields();
        assertTrue(Arrays.asList(fields).contains("eud_uid"));
        assertTrue(Arrays.asList(fields).contains("preference_key"));
        assertEquals("preference_key", OtsSection.PROFILES.titleField());
    }

    @Test
    public void aDataPackageRowShowsItsHash() {
        assertTrue(Arrays.asList(OtsSection.DATA_PACKAGES.fields())
                .contains("hash"));
    }

    @Test
    public void onlySomeSectionsNeedTheAdministratorRole() {
        // Groups, profiles and the server plugin list are administrator-only
        // server-side. Scheduler and data packages are not, so gating their
        // buttons on admin would withhold something the server allows.
        assertTrue(OtsSection.GROUPS.needsAdmin());
        assertTrue(OtsSection.PROFILES.needsAdmin());
        assertTrue(OtsSection.PLUGINS.needsAdmin());
        assertFalse(OtsSection.SCHEDULER.needsAdmin());
        assertFalse(OtsSection.DATA_PACKAGES.needsAdmin());
        assertFalse(OtsSection.USERS.needsAdmin());
    }
}
