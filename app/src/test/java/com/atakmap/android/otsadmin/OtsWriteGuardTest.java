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
 * Each rule here mirrors one the server enforces, so the refusal arrives
 * before a destructive confirmation rather than after it.
 */
public class OtsWriteGuardTest {

    @Test
    public void youCannotDeleteTheAccountYouAreLoggedInAs() {
        // The most likely one to be hit: the account the plugin logs in with
        // is the obvious one to tidy up, and the server refuses it.
        assertNotNull(OtsWriteGuard.blockDeleteUser("admin", "admin",
                false));
        // Case-insensitively, because a list may show it in a different case
        // to the one typed at login.
        assertNotNull(OtsWriteGuard.blockDeleteUser("Admin", "admin",
                false));
        assertNotNull(OtsWriteGuard.blockDeleteUser(" admin ", "admin",
                false));
        // Somebody else is fine.
        assertNull(OtsWriteGuard.blockDeleteUser("mallory", "admin",
                false));
    }

    @Test
    public void theAnonGroupCannotBeDeleted() {
        assertNotNull(OtsWriteGuard.blockDeleteGroup("__ANON__"));
        assertTrue(OtsWriteGuard.blockDeleteGroup("__ANON__")
                .contains("built in"));
        assertNull(OtsWriteGuard.blockDeleteGroup("Bravo"));
        // Only the exact name. A group somebody called "__ANON__ backup" is
        // theirs to delete.
        assertNull(OtsWriteGuard.blockDeleteGroup("__ANON__ backup"));
    }

    @Test
    public void directionIsInOrOutAndNothingElse() {
        // BOTH is what TAK convention would lead you to send, and the
        // server rejects it outright.
        assertTrue(OtsWriteGuard.validDirection("IN"));
        assertTrue(OtsWriteGuard.validDirection("OUT"));
        assertTrue(OtsWriteGuard.validDirection("in"));
        assertTrue(OtsWriteGuard.validDirection(" out "));
        assertFalse(OtsWriteGuard.validDirection("BOTH"));
        assertFalse(OtsWriteGuard.validDirection(""));
        assertFalse(OtsWriteGuard.validDirection(null));
    }

    @Test
    public void ldapModeBlocksEveryUserAndGroupWrite() {
        // When OTS_ENABLE_LDAP is set the server refuses all of these with
        // "use your LDAP server". The plugin cannot read that setting, so it
        // learns it from the first refusal and stops offering the controls.
        assertNotNull(OtsWriteGuard.blockAddUser("a", "p", "p", null, true,
                true));
        assertNotNull(OtsWriteGuard.blockDeleteUser("a", "admin", true));
        assertNotNull(OtsWriteGuard.blockGroupMembers(
                Collections.singletonList("a"), "g", "IN", true));
        for (String msg : new String[] {
                OtsWriteGuard.blockAddUser("a", "p", "p", null, true, true),
                OtsWriteGuard.blockDeleteUser("a", "admin", true) })
            assertTrue(msg, msg.contains("LDAP"));
        // Group creation is NOT blocked by LDAP server-side, only user and
        // membership operations are - so it must not be blocked here either.
        assertNull(OtsWriteGuard.blockAddGroup("Bravo"));
    }

    @Test
    public void anLdapRefusalIsRecognisedFromTheServersWording() {
        // Matched on the word, not the sentence: the messages go through
        // gettext and could be translated, but "LDAP" will not change.
        assertTrue(OtsWriteGuard.looksLikeLdapRefusal(
                "LDAP is enabled, please use your LDAP server to create"
                        + " users"));
        assertTrue(OtsWriteGuard.looksLikeLdapRefusal(
                "ldap is enabled"));
        assertFalse(OtsWriteGuard.looksLikeLdapRefusal(
                "Passwords do not match"));
        assertFalse(OtsWriteGuard.looksLikeLdapRefusal(null));
    }

    @Test
    public void mismatchedPasswordsAreCaughtBeforeTheRequest() {
        assertNotNull(OtsWriteGuard.blockAddUser("alice", "a", "b", null,
                true, false));
        assertTrue(OtsWriteGuard.blockAddUser("alice", "a", "b", null, true,
                false).contains("do not match"));
        assertNull(OtsWriteGuard.blockAddUser("alice", "a", "a", null, true,
                false));
    }

    @Test
    public void grantingAdministratorNeedsAdministrator() {
        assertNotNull(OtsWriteGuard.blockAddUser("alice", "p", "p",
                Arrays.asList("user", "administrator"), false, false));
        assertNull(OtsWriteGuard.blockAddUser("alice", "p", "p",
                Arrays.asList("user", "administrator"), true, false));
        // A plain user is fine either way.
        assertNull(OtsWriteGuard.blockAddUser("alice", "p", "p",
                Collections.singletonList("user"), false, false));
    }

    @Test
    public void emptyInputIsRefusedWithSomethingSayable() {
        String[] messages = {
                OtsWriteGuard.blockAddUser("", "p", "p", null, true, false),
                OtsWriteGuard.blockAddUser("a", "", "", null, true, false),
                OtsWriteGuard.blockDeleteUser("", "admin", false),
                OtsWriteGuard.blockDeleteUser(null, "admin", false),
                OtsWriteGuard.blockAddGroup(" "),
                OtsWriteGuard.blockDeleteGroup(null),
                OtsWriteGuard.blockGroupMembers(null, "g", "IN", false),
                OtsWriteGuard.blockGroupMembers(
                        Collections.singletonList("a"), "", "IN", false),
                OtsWriteGuard.blockGroupMembers(
                        Collections.singletonList("a"), "g", "BOTH",
                        false) };
        for (String m : messages) {
            assertNotNull(m);
            assertFalse(m.isEmpty());
            // Written for somebody holding a phone, not a stack trace.
            assertTrue(m, m.endsWith("."));
        }
    }

    @Test
    public void aValidWriteIsNotBlocked() {
        assertNull(OtsWriteGuard.blockAddUser("alice", "p", "p",
                Collections.singletonList("user"), true, false));
        assertNull(OtsWriteGuard.blockAddGroup("Bravo"));
        assertNull(OtsWriteGuard.blockDeleteGroup("Bravo"));
        assertNull(OtsWriteGuard.blockGroupMembers(
                Arrays.asList("alice", "bob"), "Bravo", "IN", false));
    }

    @Test
    public void everythingDestructiveAsksFirst() {
        assertTrue(OtsWriteGuard.needsConfirmation("delete user"));
        assertTrue(OtsWriteGuard.needsConfirmation("Delete group"));
        assertTrue(OtsWriteGuard.needsConfirmation("remove from group"));
        assertFalse(OtsWriteGuard.needsConfirmation("add user"));
        assertFalse(OtsWriteGuard.needsConfirmation("create group"));
        // Unknown operations ask. Creating a user is undoable by deleting
        // it; the reverse is not, so the default leans that way.
        assertTrue(OtsWriteGuard.needsConfirmation(null));
        assertTrue(OtsWriteGuard.needsConfirmation("something new"));
    }

    @Test
    public void theConstantsMatchWhatTheServerExpects() {
        assertEquals("__ANON__", OtsWriteGuard.ANON_GROUP);
        assertEquals("IN", OtsWriteGuard.DIRECTION_IN);
        assertEquals("OUT", OtsWriteGuard.DIRECTION_OUT);
        assertEquals("administrator", OtsWriteGuard.ROLE_ADMIN);
        assertEquals("user", OtsWriteGuard.ROLE_USER);
    }
}
