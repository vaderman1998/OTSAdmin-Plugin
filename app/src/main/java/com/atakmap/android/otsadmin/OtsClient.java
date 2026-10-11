package com.atakmap.android.otsadmin;

import com.atakmap.coremap.log.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The HTTP side of talking to an OpenTAKServer.
 *
 * <h3>No certificate bypass, deliberately</h3>
 * There is no "trust all certificates" switch and there will not be one.
 * This connection carries an administrator's password and then a token that
 * can delete every account on the server; accepting any certificate offered
 * is how those get handed to whoever controls the network. A server with a
 * proper CA certificate works out of the box. A self-signed one needs its
 * certificate pinned, which is a feature worth writing properly rather than
 * a flag worth adding carelessly.
 *
 * <h3>The error body matters more than the status code</h3>
 * OTS answers a refused write with HTTP 400 and a JSON body saying why, in
 * wording written for a person. {@link HttpURLConnection} throws away the
 * body on a 4xx unless you read the error stream, so this always does —
 * without it every refusal becomes "HTTP 400" and the operator is left
 * guessing between a bad password, a duplicate name and LDAP being in
 * charge.
 */
public class OtsClient {

    private static final String TAG = "OtsAdmin";
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 20000;

    /** What a call came back with. */
    public static final class Result {
        public final int code;
        public final String body;

        Result(int code, String body) {
            this.code = code;
            this.body = body;
        }

        public boolean ok() {
            return code >= 200 && code < 300;
        }

        /**
         * The best available explanation of a failure, or null on success.
         *
         * Prefers the server's own wording and only falls back to the status
         * code when there is nothing better — a reverse proxy answering with
         * an HTML page, usually.
         */
        public String failure() {
            if (ok())
                return null;
            String fromBody = OtsResponse.error(body);
            if (fromBody != null)
                return fromBody;
            if (code == 401 || code == 403)
                return "the server rejected the login (" + code + ")";
            if (code == 0)
                return body;
            return "the server returned " + code;
        }
    }

    private final String baseUrl;
    private volatile String token;

    public OtsClient(String baseUrl) {
        this.baseUrl = OtsRequest.normalizeBase(baseUrl);
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public boolean hasToken() {
        return token != null && !token.isEmpty();
    }

    /**
     * Send a request. Never throws: a transport failure comes back as code 0
     * with the reason in the body, because the caller has to render it either
     * way and a background thread that throws just loses the message.
     */
    public Result send(OtsRequest request) {
        if (baseUrl.isEmpty())
            return new Result(0, "no server address is set");
        HttpURLConnection conn = null;
        try {
            URL url = new URL(request.url(baseUrl));
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod(request.method.name());
            conn.setRequestProperty("Accept", "application/json");
            // The header Flask-Security looks for. Sent on everything except
            // the login itself, which has nothing to send yet.
            if (hasToken())
                conn.setRequestProperty("Authentication-Token", token);

            if (request.hasBody()) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                byte[] payload = request.body
                        .getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(payload.length);
                OutputStream out = conn.getOutputStream();
                try {
                    out.write(payload);
                    out.flush();
                } finally {
                    out.close();
                }
            }

            int code = conn.getResponseCode();
            // On a 4xx the body is on the error stream, and the body is the
            // whole point - see the class note.
            String body = read(code >= 400 ? conn.getErrorStream()
                    : conn.getInputStream());
            if (!(code >= 200 && code < 300))
                Log.w(TAG, request.method + " " + request.path + " -> " + code
                        + (request.hasBody()
                                ? " body=" + OtsJson.redact(request.body)
                                : ""));
            return new Result(code, body);
        } catch (Exception e) {
            String why = e.getMessage() == null ? e.getClass()
                    .getSimpleName() : e.getMessage();
            Log.w(TAG, "request failed: " + request.method + " "
                    + request.path + " - " + why);
            return new Result(0, why);
        } finally {
            if (conn != null)
                conn.disconnect();
        }
    }

    private static String read(InputStream in) {
        if (in == null)
            return "";
        StringBuilder sb = new StringBuilder();
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            try {
                String line;
                while ((line = reader.readLine()) != null)
                    sb.append(line).append('\n');
            } finally {
                reader.close();
            }
        } catch (IOException e) {
            // A truncated body is still worth whatever arrived.
            Log.w(TAG, "could not read the response body", e);
        }
        return sb.toString();
    }

    /** For a status line: host only, so a pane does not show the scheme. */
    public String hostLabel() {
        try {
            return new URL(baseUrl).getHost();
        } catch (Exception e) {
            return baseUrl;
        }
    }

    /** Only used by the diagnostics line; never logged with values. */
    static String describe(Map<String, String> query) {
        return query.isEmpty() ? "" : query.keySet().toString();
    }
}
