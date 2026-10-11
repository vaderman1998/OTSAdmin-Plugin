package com.atakmap.android.otsadmin;

import java.util.List;
import java.util.Locale;

/**
 * The rules that stop a write the server would refuse anyway, and say why
 * before it is sent.
 *
 * <h3>Why check locally at all</h3>
 * Not to second-guess the server — it is the authority and its answer is
 * always shown verbatim. This exists because of <i>when</i> the refusal
 * arrives. These operations are destructive and they are being done on a
 * phone, often one-handed: finding out that you cannot delete your own
 * account only <b>after</b> confirming a delete is a worse experience than
 * the button being disabled with the reason next to it. Every rule here is
 * read off the server's own blueprints, so none of them is a local opinion.
 *
 * <h3>The rules, and where each comes from</h3>
 * <ul>
 *   <li><b>You cannot delete your own account.</b>
 *       {@code user_api.delete_user} compares against
 *       {@code current_user.username}. This is the one most likely to be hit
 *       in practice, because the account the plugin logs in with is the
 *       obvious one to tidy up.</li>
 *   <li><b>The {@code __ANON__} group cannot be deleted.</b>
 *       {@code group_api.delete_group} refuses it by name.</li>
 *   <li><b>Direction is IN or OUT.</b> {@code add_user_to_group} rejects
 *       anything else outright — including {@code BOTH}, which is what TAK
 *       convention would lead you to send.</li>
 *   <li><b>LDAP mode blocks every user and group write.</b> When
 *       {@code OTS_ENABLE_LDAP} is set, user creation, deletion and group
 *       membership all return 400 and tell you to use the LDAP server. The
 *       plugin cannot read that setting, so it learns it from the first
 *       refusal and then stops offering the controls.</li>
 *   <li><b>Granting administrator needs administrator.</b> The server checks
 *       {@code current_user.has_role}.</li>
 * </ul>
 *
 * Plain Java, no Android imports.
 */
public final class OtsWriteGuard {

    private OtsWriteGuard() {
    }

    /** The group OTS keeps for unauthenticated clients. */
    public static final String ANON_GROUP = "__ANON__";
    public static final String DIRECTION_IN = "IN";
    public static final String DIRECTION_OUT = "OUT";
    public static final String ROLE_ADMIN = "administrator";
    public static final String ROLE_USER = "user";

    /** Why a write will not be attempted, or null when it is allowed. */
    public static String blockAddUser(String username, String password,
            String confirmPassword, List<String> roles,
            boolean callerIsAdmin, boolean ldapEnabled) {
        if (ldapEnabled)
            return "LDAP is handling accounts on this server, so users"
                    + " cannot be created here.";
        if (isBlank(username))
            return "A username is needed.";
        if (isBlank(password))
            return "A password is needed.";
        if (!password.equals(confirmPassword))
            return "The two passwords do not match.";
        if (roles != null && roles.contains(ROLE_ADMIN) && !callerIsAdmin)
            return "Only an administrator can grant the administrator role.";
        return null;
    }

    /**
     * @param username    the account to delete
     * @param loggedInAs  the account this plugin is logged in as
     */
    public static String blockDeleteUser(String username, String loggedInAs,
            boolean ldapEnabled) {
        if (ldapEnabled)
            return "LDAP is handling accounts on this server, so users"
                    + " cannot be deleted here.";
        if (isBlank(username))
            return "No user chosen.";
        // Case-insensitively, because the server compares the stored
        // username and a list may well show it in a different case to the
        // one that was typed at login.
        if (loggedInAs != null
                && username.trim().equalsIgnoreCase(loggedInAs.trim()))
            return "This is the account OTS Admin is logged in as. Deleting"
                    + " it would lock you out, and the server refuses it.";
        return null;
    }

    public static String blockAddGroup(String name) {
        if (isBlank(name))
            return "A group name is needed.";
        return null;
    }

    public static String blockDeleteGroup(String name) {
        if (isBlank(name))
            return "No group chosen.";
        if (ANON_GROUP.equals(name.trim()))
            return ANON_GROUP + " is built in and cannot be deleted.";
        return null;
    }

    public static String blockGroupMembers(List<String> usernames,
            String groupName, String direction, boolean ldapEnabled) {
        if (ldapEnabled)
            return "LDAP is handling group membership on this server.";
        if (usernames == null || usernames.isEmpty())
            return "No users chosen.";
        if (isBlank(groupName))
            return "No group chosen.";
        if (!validDirection(direction))
            return "Direction must be IN or OUT.";
        return null;
    }

    /** IN or OUT. Deliberately NOT accepting BOTH — the server does not. */
    public static boolean validDirection(String direction) {
        if (direction == null)
            return false;
        String d = direction.trim().toUpperCase(Locale.US);
        return DIRECTION_IN.equals(d) || DIRECTION_OUT.equals(d);
    }

    /**
     * Whether a refusal from the server means LDAP is running the show.
     *
     * Matched on the distinctive word rather than the whole sentence,
     * because the server's messages go through {@code gettext} and could be
     * translated or reworded; "LDAP" is the part that will not change.
     */
    public static boolean looksLikeLdapRefusal(String serverError) {
        return serverError != null
                && serverError.toUpperCase(Locale.US).contains("LDAP");
    }

    /**
     * Whether an operation should ask before it runs.
     *
     * Everything that removes something. Creating a user or a group is
     * recoverable by deleting it; the reverse is not, and on a phone the
     * button you did not mean to press is the normal failure.
     */
    public static boolean needsConfirmation(String operation) {
        if (operation == null)
            return true;
        // An allow-list, not a deny-list. Listing the destructive words
        // instead would mean any operation added later defaulted to NOT
        // asking, which is the wrong way for that mistake to go.
        String o = operation.toLowerCase(Locale.US);
        return !(o.contains("add") || o.contains("create"));
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
