package com.atakmap.android.otsadmin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/**
 * Every assertion here is read off OpenTAKServer's own blueprints
 * (opentakserver/blueprints/ots_api/, 2026-10-10), because the plugin cannot
 * be pointed at a live server from a unit test and getting any of this wrong
 * produces a 400 that reads like bad input rather than a misplaced field.
 */
public class OtsRequestTest {

    private static final String BASE = "https://tak.example.org";

    // ------------------------------------------------------------------
    // THE trap: creates take a JSON body, deletes take query parameters
    // ------------------------------------------------------------------

    @Test
    public void creatingAGroupSendsAJsonBodyWithTheFieldCalledName() {
        OtsRequest r = OtsRequest.addGroup("Bravo", "second squad");
        assertEquals(OtsRequest.Method.POST, r.method);
        assertEquals(BASE + "/api/groups", r.url(BASE));
        assertTrue(r.hasBody());
        assertEquals("{\"name\":\"Bravo\",\"description\":\"second squad\"}",
                r.body);
    }

    @Test
    public void deletingAGroupSendsAQueryParamCalledGroupName() {
        // Not a JSON body, and NOT called "name". delete_group reads
        // request.args.get("group_name"); sent as JSON it answers "Missing
        // group name", which reads like the name was wrong rather than in
        // the wrong place entirely.
        OtsRequest r = OtsRequest.deleteGroup("Bravo");
        assertEquals(OtsRequest.Method.DELETE, r.method);
        assertEquals(BASE + "/api/groups?group_name=Bravo", r.url(BASE));
        assertFalse(r.hasBody());
        assertNull(r.body);
    }

    @Test
    public void theTwoGroupCallsDoNotShareAFieldName() {
        // Guards the pair against someone "tidying up" the duplication.
        assertTrue(OtsRequest.addGroup("x", null).body.contains("\"name\""));
        assertTrue(OtsRequest.deleteGroup("x").url(BASE)
                .contains("group_name="));
        assertFalse(OtsRequest.deleteGroup("x").url(BASE)
                .contains("?name="));
    }

    @Test
    public void bothDirectionsIsTwoRequestsBecauseTheServerHasNoBoth() {
        // add_user_to_group rejects anything that is not exactly IN or OUT.
        // Both is still reachable: groups_users is keyed on
        // (user_id, group_id, DIRECTION), so direction is part of a
        // membership's identity and a user can hold two rows for one group.
        java.util.List<OtsRequest> both = OtsRequest.addGroupMembersBoth(
                Arrays.asList("alice"), "Bravo");
        assertEquals(2, both.size());
        assertTrue(both.get(0).body.contains("\"direction\":\"IN\""));
        assertTrue(both.get(1).body.contains("\"direction\":\"OUT\""));
        for (OtsRequest r : both) {
            // Both go to the same place as a single-direction add.
            assertEquals(OtsRequest.Method.PUT, r.method);
            assertEquals(BASE + "/api/groups", r.url(BASE));
            assertTrue(r.body.contains("\"users\":[\"alice\"]"));
            assertTrue(r.body.contains("\"group_name\":\"Bravo\""));
            // And neither ever sends the word BOTH, which earns a 400.
            assertFalse(r.body, r.body.contains("BOTH"));
        }
    }

    @Test
    public void theGroupsCollectionCarriesThreeDifferentVerbs() {
        // POST creates, PUT adds a member, DELETE removes the group - all on
        // /api/groups, which is why none of them can share a builder. Only
        // the member REMOVAL uses the members sub-resource.
        assertEquals(OtsRequest.Method.POST,
                OtsRequest.addGroup("Bravo", null).method);
        assertEquals(OtsRequest.Method.PUT,
                OtsRequest.addGroupMembers(
                        Arrays.asList("alice"), "Bravo", "IN").method);
        assertEquals(OtsRequest.Method.DELETE,
                OtsRequest.deleteGroup("Bravo").method);
        assertEquals(BASE + "/api/groups/members?username=alice"
                + "&group_name=Bravo&direction=IN",
                OtsRequest.removeGroupMember("alice", "Bravo", "IN")
                        .url(BASE));
    }

    @Test
    public void addingGroupMembersSendsUsersAsAnArray() {
        // users is a LIST server-side. A bare string would be iterated as
        // characters or rejected outright.
        OtsRequest r = OtsRequest.addGroupMembers(
                Arrays.asList("alice", "bob"), "Bravo", "IN");
        // Shipped wrong in 0.1.0 as POST /api/groups/members, a route
        // that does not exist - that sub-resource has only GET and DELETE.
        assertEquals(OtsRequest.Method.PUT, r.method);
        assertEquals(BASE + "/api/groups", r.url(BASE));
        assertEquals("{\"users\":[\"alice\",\"bob\"],"
                + "\"group_name\":\"Bravo\",\"direction\":\"IN\"}", r.body);
    }

    @Test
    public void removingAGroupMemberUsesQueryParamsOneUserAtATime() {
        OtsRequest r = OtsRequest.removeGroupMember("alice", "Bravo", "IN");
        assertEquals(OtsRequest.Method.DELETE, r.method);
        assertEquals(BASE + "/api/groups/members?username=alice"
                + "&group_name=Bravo&direction=IN", r.url(BASE));
        assertFalse(r.hasBody());
    }

    @Test
    public void deletingAUserIsAPostWithABodyNotADelete() {
        // The one inconsistency in the other direction: user deletion is
        // POST /api/user/delete reading request.json, unlike the group
        // deletes. Assuming symmetry here would send a DELETE to a route
        // that does not accept one.
        OtsRequest r = OtsRequest.deleteUser("mallory");
        assertEquals(OtsRequest.Method.POST, r.method);
        assertEquals(BASE + "/api/user/delete", r.url(BASE));
        assertEquals("{\"username\":\"mallory\"}", r.body);
    }

    // ------------------------------------------------------------------
    // Users
    // ------------------------------------------------------------------

    @Test
    public void addingAUserAlwaysSendsConfirmPassword() {
        // create_user compares password against confirm_password and
        // rejects a mismatch, so omitting it fails every time.
        OtsRequest r = OtsRequest.addUser("alice", "hunter2", null);
        assertEquals(BASE + "/api/user/add", r.url(BASE));
        assertEquals("{\"username\":\"alice\",\"password\":\"hunter2\","
                + "\"confirm_password\":\"hunter2\"}", r.body);
    }

    @Test
    public void rolesAreOmittedWhenEmptySoTheServerDefaultApplies() {
        // create_user falls back to ["user"] when roles is absent, which is
        // the behaviour we want rather than hardcoding it here.
        assertFalse(OtsRequest.addUser("a", "b", null).body
                .contains("roles"));
        assertFalse(OtsRequest.addUser("a", "b",
                Collections.<String>emptyList()).body.contains("roles"));
        assertTrue(OtsRequest.addUser("a", "b",
                Arrays.asList("user", "administrator")).body
                .contains("\"roles\":[\"user\",\"administrator\"]"));
    }

    @Test
    public void aPasswordWithQuotesOrBackslashesSurvives() {
        // Perfectly legal in a password, and unescaped it produces a body
        // the server cannot parse - reported back as a failed login, which
        // looks like the password is simply wrong.
        OtsRequest r = OtsRequest.addUser("alice", "a\"b\\c", null);
        // The escaped form appears twice: once for password, once for
        // confirm_password, which is what the server compares.
        assertEquals(2, countOf(r.body, "\\\"b\\\\c"));
        assertEquals(1, countOf(r.body, "confirm_password"));
        // And the raw, unescaped form appears nowhere.
        assertFalse(r.body, r.body.contains("a\"b\\c"));
    }

    // ------------------------------------------------------------------
    // Login
    // ------------------------------------------------------------------

    /** How many times {@code needle} occurs in {@code haystack}. */
    private static int countOf(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0;
                i = haystack.indexOf(needle, i + needle.length()))
            n++;
        return n;
    }

    @Test
    public void loginAsksForTheTokenExplicitly() {
        // Without include_auth_token a successful login returns a session
        // cookie and nothing the plugin can use on later calls.
        OtsRequest r = OtsRequest.login("admin", "s3cret");
        assertEquals(OtsRequest.Method.POST, r.method);
        assertTrue(r.url(BASE), r.url(BASE).contains("include_auth_token"));
        assertEquals(BASE + "/api/login?include_auth_token=", r.url(BASE));
        assertEquals("{\"username\":\"admin\",\"password\":\"s3cret\"}",
                r.body);
    }

    @Test
    public void healthIsTheRightFirstCallBecauseItNeedsNoAuth() {
        // It separates "cannot reach the server" from "bad credentials",
        // which are the two failures a new setup actually hits.
        assertEquals(BASE + "/api/health", OtsRequest.health().url(BASE));
        assertEquals(OtsRequest.Method.GET, OtsRequest.health().method);
        assertFalse(OtsRequest.health().hasBody());
    }

    // ------------------------------------------------------------------
    // URLs
    // ------------------------------------------------------------------

    @Test
    public void aBaseUrlIsTidiedAndHttpsIsAssumed() {
        // A typo that silently downgraded the scheme would put an
        // administrator's password on the wire in clear.
        assertEquals("https://tak.example.org",
                OtsRequest.normalizeBase("tak.example.org"));
        assertEquals("https://tak.example.org",
                OtsRequest.normalizeBase("https://tak.example.org/"));
        assertEquals("https://tak.example.org",
                OtsRequest.normalizeBase("  https://tak.example.org///  "));
        // An explicit http:// is left alone - it is a deliberate choice on a
        // lab network, and silently rewriting it would be its own surprise.
        assertEquals("http://192.168.1.10:8081",
                OtsRequest.normalizeBase("http://192.168.1.10:8081"));
        assertEquals("", OtsRequest.normalizeBase(null));
        assertEquals("", OtsRequest.normalizeBase("   "));
    }

    @Test
    public void aPortAndASubPathSurviveNormalisation() {
        assertEquals("https://tak.example.org:8443",
                OtsRequest.normalizeBase("https://tak.example.org:8443/"));
    }

    @Test
    public void queryValuesAreEncoded() {
        // Group names are free text server-side, so spaces and ampersands
        // are possible and would otherwise truncate the URL.
        OtsRequest r = OtsRequest.deleteGroup("Search & Rescue");
        assertEquals(BASE + "/api/groups?group_name=Search+%26+Rescue",
                r.url(BASE));
    }

    @Test
    public void pagingIsOmittedWhenItWouldOnlyRestateTheDefault() {
        assertEquals(BASE + "/api/users", OtsRequest.users(1, 0).url(BASE));
        assertEquals(BASE + "/api/users?per_page=50",
                OtsRequest.users(1, 50).url(BASE));
        assertEquals(BASE + "/api/users?page=3&per_page=50",
                OtsRequest.users(3, 50).url(BASE));
    }

    @Test
    public void theFlatListsAskForThemExplicitly() {
        // Both of these return a BARE ARRAY, not a page - see OtsResponse.
        assertEquals(BASE + "/api/eud?all=1",
                OtsRequest.allEuds().url(BASE));
        assertEquals(BASE + "/api/users/all",
                OtsRequest.allUsers().url(BASE));
    }

    @Test
    public void groupMembersIsQueriedWithNameNotGroupName() {
        // get_group_members reads request.args.get("name") - a third
        // spelling of the same idea on the same resource.
        assertEquals(BASE + "/api/groups/members?name=Bravo",
                OtsRequest.groupMembers("Bravo").url(BASE));
    }

    @Test
    public void everyReadEndpointIsAGetWithNoBody() {
        OtsRequest[] reads = {
                OtsRequest.users(1, 0), OtsRequest.groups(1, 0),
                OtsRequest.euds(1, 0), OtsRequest.eudStats(1, 0),
                OtsRequest.dataPackages(1, 0), OtsRequest.videoStreams(1, 0),
                OtsRequest.certificates(1, 0),
                OtsRequest.deviceProfiles(1, 0), OtsRequest.plugins(),
                OtsRequest.schedulerJobs(), OtsRequest.missions(1, 0),
                OtsRequest.me(), OtsRequest.status(), OtsRequest.health(),
                OtsRequest.allEuds(), OtsRequest.allUsers(),
                OtsRequest.groupMembers("x") };
        for (OtsRequest r : reads) {
            assertEquals(r.path, OtsRequest.Method.GET, r.method);
            assertFalse(r.path, r.hasBody());
            assertTrue(r.path, r.path.startsWith("/api/"));
        }
    }

    @Test
    public void nullsInWritesDoNotProduceTheWordNull() {
        // "null" is a perfectly valid group name as far as a URL is
        // concerned, so a null slipping through would delete nothing and
        // report nothing wrong.
        assertEquals(BASE + "/api/groups?group_name=",
                OtsRequest.deleteGroup(null).url(BASE));
        assertEquals("{\"username\":\"\"}",
                OtsRequest.deleteUser(null).body);
        assertFalse(OtsRequest.addGroup(null, null).body.contains("null"));
    }

    // ------------------------------------------------------------------
    // Reading a user's groups: the reverse lookup
    // ------------------------------------------------------------------

    @Test
    public void userGroupsIsAQueryParamOnThePluralUsersRoute() {
        // Note "users", plural, unlike every other /api/user/* endpoint, and
        // username as a QUERY parameter where the PUT on the same route takes
        // it in a JSON body.
        OtsRequest r = OtsRequest.userGroups("medic1");
        assertEquals(OtsRequest.Method.GET, r.method);
        assertEquals(BASE + "/api/users/groups?username=medic1", r.url(BASE));
        assertFalse(r.hasBody());
    }

    @Test
    public void userGroupsSurvivesAnAwkwardUsername() {
        OtsRequest r = OtsRequest.userGroups("a b&c");
        assertTrue("username must be encoded, not pasted in raw",
                r.url(BASE).contains("username=a%20b%26c")
                        || r.url(BASE).contains("username=a+b%26c"));
    }

    @Test
    public void userGroupsTreatsNullAsEmptyRatherThanWritingNull() {
        assertEquals(BASE + "/api/users/groups?username=",
                OtsRequest.userGroups(null).url(BASE));
    }
}
