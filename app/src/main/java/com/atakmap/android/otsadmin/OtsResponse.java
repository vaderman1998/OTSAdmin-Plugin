package com.atakmap.android.otsadmin;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Reading what OpenTAKServer sends back.
 *
 * <h3>Three different envelopes, which is the whole difficulty</h3>
 * <ul>
 *   <li><b>Paginated lists</b> — everything built by the server's
 *       {@code paginate()} helper:
 *       {@code {"results":[…],"total_pages":n,"current_page":n,
 *       "per_page":n,"total":n}}</li>
 *   <li><b>Plain arrays</b> — {@code /api/eud?all=1} and
 *       {@code /api/users/all} return a bare JSON array with no envelope at
 *       all, so code expecting {@code results} finds nothing and reports an
 *       empty server.</li>
 *   <li><b>Errors</b> — {@code {"success":false,"error":"…"}}, usually with
 *       HTTP 400. The {@code error} string is written for a person and is
 *       worth showing verbatim rather than replacing with our own wording:
 *       "You can't delete your own account" explains itself better than
 *       anything we would substitute.</li>
 * </ul>
 * Login is a fourth shape of its own — see {@link #authToken}.
 *
 * Pure Java on org.json, so it is unit-testable off device. Nothing here
 * throws on unexpected input: a server that answers something new should
 * leave the pane saying so, not take ATAK down.
 */
public final class OtsResponse {

    private OtsResponse() {
    }

    /** One page of a list. */
    public static final class Page {
        public final List<JSONObject> results;
        public final int totalPages;
        public final int currentPage;
        public final int perPage;
        public final int total;

        Page(List<JSONObject> results, int totalPages, int currentPage,
                int perPage, int total) {
            this.results = results;
            this.totalPages = totalPages;
            this.currentPage = currentPage;
            this.perPage = perPage;
            this.total = total;
        }

        public boolean hasMore() {
            return currentPage < totalPages;
        }

        public boolean isEmpty() {
            return results.isEmpty();
        }
    }

    /**
     * Parse a paginated list, tolerating a bare array.
     *
     * Accepting both shapes here rather than at each call site is deliberate:
     * which endpoints are enveloped is a property of the server, it has
     * already proved inconsistent, and getting it wrong looks like an empty
     * server rather than like a bug.
     */
    public static Page page(String json) throws JSONException {
        String body = json == null ? "" : json.trim();
        List<JSONObject> rows = new ArrayList<>();
        if (body.startsWith("[")) {
            JSONArray arr = new JSONArray(body);
            collect(arr, rows);
            return new Page(rows, 1, 1, rows.size(), rows.size());
        }
        JSONObject o = new JSONObject(body);
        collect(o.optJSONArray("results"), rows);
        int total = o.optInt("total", rows.size());
        return new Page(rows,
                Math.max(1, o.optInt("total_pages", 1)),
                Math.max(1, o.optInt("current_page", 1)),
                o.optInt("per_page", rows.size()),
                total);
    }

    private static void collect(JSONArray arr, List<JSONObject> into) {
        if (arr == null)
            return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject row = arr.optJSONObject(i);
            if (row != null)
                into.add(row);
        }
    }

    /**
     * The token from a successful login, or null.
     *
     * The shape is {@code response.user.authentication_token}, nested two
     * deep inside a Flask-Security envelope that also carries a
     * {@code csrf_token} at the {@code response} level — which is NOT the
     * one to send, and picking it up by mistake produces a plugin that
     * authenticates as nobody and gets 401s that look like a bad password.
     */
    public static String authToken(String json) {
        if (json == null || json.trim().isEmpty())
            return null;
        try {
            JSONObject response = new JSONObject(json)
                    .optJSONObject("response");
            if (response == null)
                return null;
            JSONObject user = response.optJSONObject("user");
            if (user == null)
                return null;
            String token = user.optString("authentication_token", "");
            return token.isEmpty() ? null : token;
        } catch (JSONException e) {
            return null;
        }
    }

    /**
     * The server's own error text, or null when the body is not an error.
     *
     * Checks {@code success} explicitly rather than treating the presence of
     * an {@code error} key as the signal, because a successful response may
     * legitimately carry a field called error — a scheduler job's last
     * failure, for one.
     */
    public static String error(String json) {
        try {
            JSONObject o = new JSONObject(json == null ? "" : json);
            if (o.has("success") && !o.optBoolean("success", false)) {
                String message = o.optString("error", "");
                return message.isEmpty() ? "the server refused it, with no"
                        + " reason given" : message;
            }
            // Flask-Security reports its own failures under meta.code.
            JSONObject meta = o.optJSONObject("meta");
            if (meta != null) {
                int code = meta.optInt("code", 200);
                if (code >= 400)
                    return fieldErrors(o, code);
            }
            return null;
        } catch (JSONException e) {
            return null;
        }
    }

    /** Flask-Security puts login failures in response.field_errors. */
    private static String fieldErrors(JSONObject root, int code) {
        JSONObject response = root.optJSONObject("response");
        JSONObject errors = response == null ? null
                : response.optJSONObject("field_errors");
        if (errors != null) {
            StringBuilder sb = new StringBuilder();
            for (java.util.Iterator<String> it = errors.keys();
                    it.hasNext();) {
                String key = it.next();
                JSONArray list = errors.optJSONArray(key);
                for (int i = 0; list != null && i < list.length(); i++) {
                    if (sb.length() > 0)
                        sb.append("; ");
                    sb.append(list.optString(i, ""));
                }
            }
            if (sb.length() > 0)
                return sb.toString();
        }
        return "the server returned " + code;
    }

    /** Whether a write succeeded: {@code {"success":true}}. */
    public static boolean succeeded(String json) {
        try {
            return new JSONObject(json == null ? "" : json)
                    .optBoolean("success", false);
        } catch (JSONException e) {
            return false;
        }
    }

    /** Roles on the logged-in account, from {@code /api/me}. */
    public static List<String> roles(String meJson) {
        List<String> out = new ArrayList<>();
        if (meJson == null || meJson.trim().isEmpty())
            return out;
        try {
            JSONObject o = new JSONObject(meJson == null ? "" : meJson);
            JSONArray roles = o.optJSONArray("roles");
            if (roles == null) {
                JSONObject response = o.optJSONObject("response");
                if (response != null)
                    roles = response.optJSONArray("roles");
            }
            for (int i = 0; roles != null && i < roles.length(); i++) {
                // Object first. optString on an object element returns the
                // object's own toString, not "", so testing for an empty
                // string would never reach the object branch and every role
                // would arrive as a blob of JSON.
                JSONObject r = roles.optJSONObject(i);
                String name = r != null ? r.optString("name", "")
                        : roles.optString(i, "");
                if (!name.isEmpty())
                    out.add(name);
            }
        } catch (JSONException e) {
            // An unreadable /api/me leaves the roles unknown, which the
            // caller must treat as "not an administrator" rather than as
            // permission.
            return out;
        }
        return out;
    }

    /** Whether those roles include administrator. */
    public static boolean isAdministrator(List<String> roles) {
        if (roles == null)
            return false;
        for (String r : roles)
            if ("administrator".equalsIgnoreCase(r.trim()))
                return true;
        return false;
    }
}
