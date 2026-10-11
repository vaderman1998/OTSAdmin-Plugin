package com.atakmap.android.otsadmin;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One call to an OpenTAKServer, built so the awkward parts of its API are
 * decided in a single tested place.
 *
 * <h3>The trap this class exists for</h3>
 * <b>OTS creates take a JSON body; OTS deletes take QUERY PARAMETERS.</b>
 * Not a guess, read off the blueprints:
 * <ul>
 *   <li>{@code POST /api/groups} reads {@code request.json.get("name")}</li>
 *   <li>{@code DELETE /api/groups} reads
 *       {@code request.args.get("group_name")}</li>
 *   <li>{@code POST /api/groups/members} reads {@code request.json} — and
 *       {@code users} there is a <b>list</b>, not a string</li>
 *   <li>{@code DELETE /api/groups/members} reads {@code request.args}</li>
 * </ul>
 * Send a delete as JSON and the server answers "Missing group name" with a
 * 400, which reads like the name was wrong rather than in the wrong place.
 * The field name changes too — {@code name} on create, {@code group_name} on
 * delete — so the two cannot share a builder.
 *
 * Plain Java, no Android imports, so every URL and body can be asserted off
 * device.
 */
public final class OtsRequest {

    public enum Method {
        GET, POST, PUT, PATCH, DELETE
    }

    public final Method method;
    /** Path only, always starting with a slash. */
    public final String path;
    /** Query parameters, in insertion order so a URL is reproducible. */
    public final Map<String, String> query;
    /** JSON body, or null for none. */
    public final String body;

    private OtsRequest(Method method, String path, Map<String, String> query,
            String body) {
        this.method = method;
        this.path = path;
        this.query = query;
        this.body = body;
    }

    /** The full URL against a base, with the query string appended. */
    public String url(String baseUrl) {
        StringBuilder sb = new StringBuilder(normalizeBase(baseUrl));
        sb.append(path);
        boolean first = true;
        for (Map.Entry<String, String> e : query.entrySet()) {
            sb.append(first ? '?' : '&');
            first = false;
            sb.append(encode(e.getKey())).append('=')
                    .append(encode(e.getValue()));
        }
        return sb.toString();
    }

    public boolean hasBody() {
        return body != null;
    }

    /**
     * A base URL with no trailing slash, and https assumed.
     *
     * Assuming https rather than http is deliberate: this call carries an
     * administrator's credentials, and a typo that silently downgraded the
     * scheme would put them on the wire in clear.
     */
    public static String normalizeBase(String baseUrl) {
        String b = baseUrl == null ? "" : baseUrl.trim();
        while (b.endsWith("/"))
            b = b.substring(0, b.length() - 1);
        if (b.isEmpty())
            return b;
        String lower = b.toLowerCase(Locale.US);
        if (!lower.startsWith("http://") && !lower.startsWith("https://"))
            return "https://" + b;
        return b;
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            // UTF-8 is required of every JVM; unreachable.
            return s;
        }
    }

    // ------------------------------------------------------------------
    // Auth
    // ------------------------------------------------------------------

    /**
     * Log in and ask for a token.
     *
     * {@code include_auth_token} is what makes the response carry
     * {@code response.user.authentication_token}; without it a successful
     * login returns a session cookie and nothing the plugin can use.
     */
    public static OtsRequest login(String username, String password) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("include_auth_token", "");
        return new OtsRequest(Method.POST, "/api/login", q,
                OtsJson.object("username", username, "password", password));
    }

    /** Who the token belongs to, and what roles it has. */
    public static OtsRequest me() {
        return get("/api/me");
    }

    /** Unauthenticated, so it is the right first call for a reachability
     *  test: it separates "cannot reach the server" from "bad password". */
    public static OtsRequest health() {
        return get("/api/health");
    }

    public static OtsRequest status() {
        return get("/api/status");
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /** Paginated list; {@code perPage} 0 leaves the server default of 10. */
    public static OtsRequest page(String path, int page, int perPage) {
        Map<String, String> q = new LinkedHashMap<>();
        if (page > 1)
            q.put("page", String.valueOf(page));
        if (perPage > 0)
            q.put("per_page", String.valueOf(perPage));
        return new OtsRequest(Method.GET, path, q, null);
    }

    public static OtsRequest users(int page, int perPage) {
        return page("/api/users", page, perPage);
    }

    public static OtsRequest groups(int page, int perPage) {
        return page("/api/groups", page, perPage);
    }

    public static OtsRequest euds(int page, int perPage) {
        return page("/api/eud", page, perPage);
    }

    public static OtsRequest eudStats(int page, int perPage) {
        return page("/api/eud_stats", page, perPage);
    }

    public static OtsRequest dataPackages(int page, int perPage) {
        return page("/api/data_packages", page, perPage);
    }

    public static OtsRequest videoStreams(int page, int perPage) {
        return page("/api/video_streams", page, perPage);
    }

    public static OtsRequest certificates(int page, int perPage) {
        return page("/api/certificate", page, perPage);
    }

    public static OtsRequest deviceProfiles(int page, int perPage) {
        return page("/api/profiles", page, perPage);
    }

    public static OtsRequest plugins() {
        return get("/api/plugins");
    }

    public static OtsRequest schedulerJobs() {
        return get("/api/scheduler/jobs");
    }

    public static OtsRequest missions(int page, int perPage) {
        return page("/api/missions", page, perPage);
    }

    /** The flat uid/callsign list, which is a plain array not a page. */
    public static OtsRequest allEuds() {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("all", "1");
        return new OtsRequest(Method.GET, "/api/eud", q, null);
    }

    /** Every username, for pickers. Also a plain array. */
    public static OtsRequest allUsers() {
        return get("/api/users/all");
    }

    /** The members of one group. Takes {@code name}, not group_name. */
    public static OtsRequest groupMembers(String groupName) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("name", groupName == null ? "" : groupName);
        return new OtsRequest(Method.GET, "/api/groups/members", q, null);
    }

    private static OtsRequest get(String path) {
        return new OtsRequest(Method.GET, path,
                new LinkedHashMap<String, String>(), null);
    }

    // ------------------------------------------------------------------
    // Writes. The only four things this plugin changes, plus membership.
    // ------------------------------------------------------------------

    /**
     * Create a user. JSON body.
     *
     * {@code confirm_password} is sent because the server compares the two
     * and rejects a mismatch; it is not optional.
     *
     * @param roles may be null or empty, which the server reads as
     *              {@code ["user"]}
     */
    public static OtsRequest addUser(String username, String password,
            List<String> roles) {
        List<String> parts = new ArrayList<>();
        parts.add(OtsJson.pair("username", username));
        parts.add(OtsJson.pair("password", password));
        parts.add(OtsJson.pair("confirm_password", password));
        if (roles != null && !roles.isEmpty())
            parts.add(OtsJson.arrayPair("roles", roles));
        return new OtsRequest(Method.POST, "/api/user/add",
                new LinkedHashMap<String, String>(), OtsJson.of(parts));
    }

    /**
     * Delete a user. JSON body — note this one is a POST, not a DELETE,
     * and so does take a body unlike the group deletes.
     */
    public static OtsRequest deleteUser(String username) {
        return new OtsRequest(Method.POST, "/api/user/delete",
                new LinkedHashMap<String, String>(),
                OtsJson.object("username", username));
    }

    /**
     * Issue a certificate, and with it an enrolment data package.
     *
     * The one genuinely consequential thing the API offers beyond editing
     * records: the server generates key material and builds a package the
     * device can be enrolled with. The field case it serves is somebody
     * standing in front of you with a phone that is not on the net yet.
     *
     * {@code uid} is optional \u2014 omitted, the certificate is for the user
     * rather than tied to one device.
     *
     * Only {@code @auth_required()} server-side, NOT administrator-gated,
     * which is worth knowing: any account this plugin can log in as can mint
     * one.
     */
    public static OtsRequest issueCertificate(String username, String uid) {
        List<String> parts = new ArrayList<>();
        parts.add(OtsJson.pair("username", username));
        if (uid != null && !uid.trim().isEmpty())
            parts.add(OtsJson.pair("uid", uid.trim()));
        return new OtsRequest(Method.POST, "/api/certificate",
                new LinkedHashMap<String, String>(), OtsJson.of(parts));
    }

    /**
     * Reset somebody's password as an administrator.
     *
     * The server's rules, mirrored in {@link OtsWriteGuard}: at least
     * {@code SECURITY_PASSWORD_LENGTH_MIN} characters (8 by default) and
     * <b>no colon or at-sign</b>. That last one is unusual enough to be
     * worth catching locally, because a password manager will happily
     * suggest one containing either.
     */
    public static OtsRequest resetPassword(String username,
            String newPassword) {
        return new OtsRequest(Method.POST, "/api/user/password/reset",
                new LinkedHashMap<String, String>(),
                OtsJson.object("username", username,
                        "new_password", newPassword));
    }

    /**
     * Turn an account off without deleting it.
     *
     * The gentler answer for a lost device than deleting the account: it
     * stops working immediately and can be turned back on. The server
     * refuses if it is already deactivated, which is worth surfacing rather
     * than treating as a failure.
     */
    public static OtsRequest deactivateUser(String username) {
        return new OtsRequest(Method.POST, "/api/user/deactivate",
                new LinkedHashMap<String, String>(),
                OtsJson.object("username", username));
    }

    public static OtsRequest activateUser(String username) {
        return new OtsRequest(Method.POST, "/api/user/activate",
                new LinkedHashMap<String, String>(),
                OtsJson.object("username", username));
    }

    /**
     * Replace an account's roles. JSON body, {@code roles} is a list.
     *
     * This REPLACES rather than adds, so sending one role removes the
     * others \u2014 the dialog shows the current set pre-selected for that
     * reason.
     */
    public static OtsRequest setUserRoles(String username,
            List<String> roles) {
        List<String> parts = new ArrayList<>();
        parts.add(OtsJson.pair("username", username));
        parts.add(OtsJson.arrayPair("roles",
                roles == null ? new ArrayList<String>() : roles));
        return new OtsRequest(Method.POST, "/api/user/role",
                new LinkedHashMap<String, String>(), OtsJson.of(parts));
    }

    /**
     * Set which groups a user belongs to, from the user's side.
     *
     * {@code PUT /api/users/groups} \u2014 note the plural {@code users}, unlike
     * every other user endpoint, and that this is the mirror of
     * {@link #addGroupMembers}: same relationship, approached from the other
     * end, and it takes a list of GROUPS for one user rather than a list of
     * users for one group.
     */
    public static OtsRequest setUserGroups(String username,
            List<String> groups, String direction) {
        List<String> parts = new ArrayList<>();
        parts.add(OtsJson.pair("username", username));
        parts.add(OtsJson.arrayPair("groups",
                groups == null ? new ArrayList<String>() : groups));
        parts.add(OtsJson.pair("direction", direction));
        return new OtsRequest(Method.PUT, "/api/users/groups",
                new LinkedHashMap<String, String>(), OtsJson.of(parts));
    }

    /** Attach an EUD to an account. */
    public static OtsRequest assignEud(String username, String uid) {
        return new OtsRequest(Method.POST, "/api/user/assign_eud",
                new LinkedHashMap<String, String>(),
                OtsJson.object("username", username, "uid", uid));
    }

    // --- Scheduler. All three take job_id in a JSON body.

    public static OtsRequest pauseJob(String jobId) {
        return job("pause", jobId);
    }

    public static OtsRequest resumeJob(String jobId) {
        return job("resume", jobId);
    }

    /** Run a job now, outside its schedule. */
    public static OtsRequest runJob(String jobId) {
        return job("run", jobId);
    }

    private static OtsRequest job(String action, String jobId) {
        return new OtsRequest(Method.POST,
                "/api/scheduler/job/" + action,
                new LinkedHashMap<String, String>(),
                OtsJson.object("job_id", jobId));
    }

    // --- Server plugins. The name is in the PATH, and there is no body.

    public static OtsRequest enableServerPlugin(String name) {
        return serverPlugin(name, "enable");
    }

    public static OtsRequest disableServerPlugin(String name) {
        return serverPlugin(name, "disable");
    }

    /**
     * The plugin name goes in the path, so it must be path-escaped rather
     * than left raw \u2014 a name with a slash in it would otherwise point at a
     * different route entirely.
     */
    private static OtsRequest serverPlugin(String name, String action) {
        String safe = name == null ? "" : name.trim().replace("/", "%2F");
        return new OtsRequest(Method.POST,
                "/api/plugins/" + safe + "/" + action,
                new LinkedHashMap<String, String>(), null);
    }

    /** Delete a data package by its hash. QUERY PARAMETER. */
    public static OtsRequest deleteDataPackage(String hash) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("hash", hash == null ? "" : hash);
        return new OtsRequest(Method.DELETE, "/api/data_packages", q, null);
    }

    /**
     * Delete one device-profile preference. QUERY PARAMETERS.
     *
     * Keyed on the pair, not on a single id: a profile is a set of
     * preferences and this removes one of them for one EUD.
     */
    public static OtsRequest deleteDeviceProfile(String eudUid,
            String preferenceKey) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("eud_uid", eudUid == null ? "" : eudUid);
        q.put("preference_key",
                preferenceKey == null ? "" : preferenceKey);
        return new OtsRequest(Method.DELETE, "/api/profiles", q, null);
    }

    /** Create a group. JSON body, field is {@code name}. */
    public static OtsRequest addGroup(String name, String description) {
        List<String> parts = new ArrayList<>();
        parts.add(OtsJson.pair("name", name));
        if (description != null && !description.isEmpty())
            parts.add(OtsJson.pair("description", description));
        return new OtsRequest(Method.POST, "/api/groups",
                new LinkedHashMap<String, String>(), OtsJson.of(parts));
    }

    /**
     * Delete a group. QUERY PARAMETER, and the field is {@code group_name},
     * not {@code name} as on create. See the class note.
     */
    public static OtsRequest deleteGroup(String groupName) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("group_name", groupName == null ? "" : groupName);
        return new OtsRequest(Method.DELETE, "/api/groups", q, null);
    }

    /**
     * Add users to a group.
     *
     * <b>This is {@code PUT /api/groups}, not a POST on the members
     * sub-resource.</b> There is no POST route on
     * {@code /api/groups/members} at all — only GET and DELETE — so the
     * obvious guess returns 405, which reads like the group name was wrong.
     * The handler is {@code add_user_to_group} and it sits on the
     * collection: {@code /api/groups} carries POST to create, PUT to add a
     * member and DELETE to remove the group, with the members sub-resource
     * used only for reading and removing.
     *
     * JSON body, and {@code users} is a <b>list</b>.
     *
     * @param direction {@code "IN"} or {@code "OUT"} — the server accepts
     *                  nothing else, not even "BOTH"
     */
    public static OtsRequest addGroupMembers(List<String> usernames,
            String groupName, String direction) {
        List<String> parts = new ArrayList<>();
        parts.add(OtsJson.arrayPair("users",
                usernames == null ? new ArrayList<String>() : usernames));
        parts.add(OtsJson.pair("group_name", groupName));
        parts.add(OtsJson.pair("direction", direction));
        return new OtsRequest(Method.PUT, "/api/groups",
                new LinkedHashMap<String, String>(), OtsJson.of(parts));
    }

    /**
     * Add users to a group in BOTH directions.
     *
     * <b>The server has no "BOTH" value</b> - {@code add_user_to_group}
     * rejects anything that is not exactly {@code IN} or {@code OUT}. But
     * both is still reachable, because of how the membership is keyed:
     * {@code groups_users} has a composite primary key of
     * <b>(user_id, group_id, direction)</b>, so direction is part of the
     * identity of a membership and one user can hold two rows for one group.
     *
     * So this is two requests, not one value. Returned as a list rather than
     * sent here, so the caller can report both outcomes - the second can
     * fail on its own and claiming success from the first would be a lie.
     *
     * The consequence to remember: a user added both ways has TWO
     * memberships, shows as two rows, and needs two removals. That is the
     * server's model, not a quirk of this plugin.
     */
    public static List<OtsRequest> addGroupMembersBoth(
            List<String> usernames, String groupName) {
        List<OtsRequest> out = new ArrayList<>();
        out.add(addGroupMembers(usernames, groupName, "IN"));
        out.add(addGroupMembers(usernames, groupName, "OUT"));
        return out;
    }

    /** Remove one user from a group. QUERY PARAMETERS, one user at a time. */
    public static OtsRequest removeGroupMember(String username,
            String groupName, String direction) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("username", username == null ? "" : username);
        q.put("group_name", groupName == null ? "" : groupName);
        q.put("direction", direction == null ? "" : direction);
        return new OtsRequest(Method.DELETE, "/api/groups/members", q, null);
    }
}
