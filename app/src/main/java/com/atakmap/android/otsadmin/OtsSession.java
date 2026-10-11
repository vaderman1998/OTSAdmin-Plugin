package com.atakmap.android.otsadmin;

import android.content.SharedPreferences;

import com.atakmap.coremap.log.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Who we are logged in as, and what that account is allowed to do.
 *
 * <h3>Connect in three steps, because the failures are different</h3>
 * <ol>
 *   <li><b>{@code /api/health}</b> — unauthenticated, so it separates
 *       "cannot reach the server" from "credentials are wrong". Those are
 *       the two things a new setup actually gets wrong and they need
 *       different advice.</li>
 *   <li><b>{@code /api/login?include_auth_token}</b> — a login that comes
 *       back without a token is its own failure: it means the server has not
 *       been configured for token auth, which needs
 *       {@code SECURITY_CSRF_PROTECT_MECHANISMS} changed in
 *       {@code ~/ots/config.yml} and a restart. Nothing the plugin can do,
 *       and worth saying so rather than reporting a bad password.</li>
 *   <li><b>{@code /api/me}</b> — the roles. Without the administrator role
 *       most of this plugin is unusable, and finding that out one endpoint at
 *       a time as 403s is worse than being told once.</li>
 * </ol>
 *
 * <h3>Where the password lives</h3>
 * ATAK's preferences, which are plain SharedPreferences and are not
 * encrypted. There is no secure store available to a plugin — see the
 * portfolio note about plugin code running as ATAK. This is stated in the
 * settings summary rather than hidden, and the recommendation is a dedicated
 * administrator account for the plugin so that losing the device costs that
 * account and not the operator's own.
 */
public class OtsSession {

    private static final String TAG = "OtsAdmin";

    public static final String PREF_URL = "pref_ots_url";
    public static final String PREF_USER = "pref_ots_username";
    public static final String PREF_PASS = "pref_ots_password";

    /** How a connection attempt ended. */
    public enum State {
        /** Nothing tried yet, or the settings are incomplete. */
        UNCONFIGURED,
        CONNECTING,
        /** Logged in. {@link #isAdmin()} says how much is usable. */
        READY,
        /** The server could not be reached at all. */
        UNREACHABLE,
        /** Reached, but the login was refused. */
        REFUSED,
        /** Logged in, but the server is not set up for token auth. */
        NO_TOKEN_AUTH
    }

    private final SharedPreferences prefs;

    private volatile OtsClient client;
    private volatile State state = State.UNCONFIGURED;
    private volatile String message = "Not configured";
    private volatile String username = "";
    private volatile List<String> roles = new ArrayList<>();
    /**
     * Set once the server refuses a write because LDAP owns accounts.
     *
     * Sticky for the session: the setting cannot change under us without a
     * server restart, and asking again every time would mean one pointless
     * refusal per attempt.
     */
    private volatile boolean ldap = false;

    public OtsSession(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    public State state() {
        return state;
    }

    public String message() {
        return message;
    }

    public String username() {
        return username;
    }

    public List<String> roles() {
        return roles;
    }

    public boolean isAdmin() {
        return OtsResponse.isAdministrator(roles);
    }

    public boolean isLdap() {
        return ldap;
    }

    public OtsClient client() {
        return client;
    }

    public boolean isReady() {
        return state == State.READY && client != null && client.hasToken();
    }

    /** Note that the server has told us LDAP is in charge. */
    public void noteLdapRefusal() {
        if (!ldap) {
            ldap = true;
            Log.i(TAG, "server reports LDAP owns accounts; user and group"
                    + " writes disabled");
        }
    }

    /** Runs on a background thread. Safe to call repeatedly. */
    public synchronized void connect() {
        String url = prefs.getString(PREF_URL, "");
        String user = prefs.getString(PREF_USER, "");
        String pass = prefs.getString(PREF_PASS, "");
        if (isBlank(url) || isBlank(user) || isBlank(pass)) {
            set(State.UNCONFIGURED, "Set the server URL, username and"
                    + " password in settings");
            return;
        }

        state = State.CONNECTING;
        message = "Connecting…";
        username = user.trim();
        roles = new ArrayList<>();
        OtsClient c = new OtsClient(url);

        // 1. Reachable at all? This is unauthenticated on purpose.
        OtsClient.Result health = c.send(OtsRequest.health());
        if (health.code == 0) {
            set(State.UNREACHABLE, "Cannot reach " + c.hostLabel() + ": "
                    + health.body);
            return;
        }

        // 2. Log in and ask for a token.
        OtsClient.Result login = c.send(
                OtsRequest.login(username, pass));
        if (!login.ok()) {
            set(State.REFUSED, "Login refused: " + login.failure());
            return;
        }
        String token = OtsResponse.authToken(login.body);
        if (token == null) {
            // Logged in, but token auth is off server-side.
            set(State.NO_TOKEN_AUTH, "Logged in, but the server sent no API"
                    + " token. Add 'session' and 'basic' to"
                    + " SECURITY_CSRF_PROTECT_MECHANISMS in ~/ots/config.yml"
                    + " and restart OpenTAKServer.");
            return;
        }
        c.setToken(token);

        // 3. What is this account allowed to do?
        OtsClient.Result me = c.send(OtsRequest.me());
        roles = OtsResponse.roles(me.body);
        client = c;
        if (isAdmin()) {
            set(State.READY, "Connected to " + c.hostLabel() + " as "
                    + username);
        } else {
            // Not an error - the read-only views still work - but it has to
            // be said, or every write fails with a 403 for no visible reason.
            set(State.READY, "Connected as " + username
                    + ", which is not an administrator: reading only");
        }
    }

    public synchronized void disconnect() {
        client = null;
        roles = new ArrayList<>();
        ldap = false;
        set(State.UNCONFIGURED, "Disconnected");
    }

    private void set(State s, String m) {
        state = s;
        message = m;
        Log.d(TAG, "session " + s + ": " + m);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
