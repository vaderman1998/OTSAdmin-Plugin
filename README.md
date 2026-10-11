# OTS Admin for ATAK

Administer an [OpenTAKServer](https://github.com/brian7704/OpenTAKServer) from inside ATAK: add and
remove users and groups, and read everything else the server's admin page shows.

**ATAK SDK:** 5.4.0 / 5.6.0 / 5.7.0 / 5.8.0 &nbsp;|&nbsp; **Min SDK:** 21 &nbsp;|&nbsp; **Version:** 0.1.0

> **Not yet run against a live server.** The API contract is implemented from OpenTAKServer's own
> blueprints and covered by 47 unit tests, but nothing here has made a real request. Treat 0.1.0 as
> untested against a server until that happens.

---

## Setting it up

**Use the same username and password you log into the web UI with.** There is no separate API
account, no API key, and nothing to generate. The plugin posts to `/api/login` — the same endpoint
the web UI's login form uses — and the only difference is the `include_auth_token` parameter, which
asks the server to hand back an API token alongside the session cookie. The web UI does not need one
because it keeps the cookie; the plugin uses the token header instead.

Set the server URL, username and password in **Settings → Tools → OTS Admin**, then press
**Connect**.

### Token authentication is already on by default

The OpenTAKServer docs describe adding this to `~/ots/config.yml`:

```yaml
SECURITY_CSRF_PROTECT_MECHANISMS:
  - session
  - basic
```

**On current OpenTAKServer that is already the default** — `defaultconfig.py` sets exactly those two
values, alongside `WTF_CSRF_CHECK_DEFAULT = False` and
`SECURITY_CSRF_IGNORE_UNAUTH_ENDPOINTS = True`. So there is normally nothing to change. The
documentation appears to predate the default.

It is still worth knowing about, for two cases: an older server, or a `config.yml` that overrides the
setting (config.yml is merged over the defaults). In either, the login succeeds and the server
returns **no API token**. The plugin detects that specific case and says so, rather than reporting it
as a bad password — which is what it looks like otherwise.

---

## What it changes, and what it only shows

**Writes — users and groups only:**

| Operation | Endpoint |
|---|---|
| Add user | `POST /api/user/add` |
| Delete user | `POST /api/user/delete` |
| Add group | `POST /api/groups` |
| Delete group | `DELETE /api/groups` |
| Add users to a group | `POST /api/groups/members` |
| Remove a user from a group | `DELETE /api/groups/members` |

**Read-only:** devices (EUDs), certificates, data packages, video streams, device profiles,
missions, scheduled jobs, the server's own plugin list, and server health.

Everything else is read-only on purpose. Those six are the operations worth doing from a phone;
every additional write is another way for a mistaken tap on a device in a pocket to cost something.

---

## Security, stated plainly

- **The password is stored in ATAK's preferences, which are not encrypted.** A plugin has no secure
  store available to it — plugin code runs inside ATAK as ATAK's UID and cannot use its own app
  storage. A phone with this configured can delete every account on your server.
- **Use a dedicated administrator account for the plugin**, not your own. Losing the device then
  costs that one account, which you can delete from the web UI, instead of your own login.
- **There is no "trust all certificates" switch, and there will not be one.** Accepting any
  certificate offered is how an administrator's credentials end up with whoever controls the
  network. A server with a proper CA certificate works as-is; a self-signed one needs its
  certificate pinned, which is a feature worth writing properly rather than a flag worth adding
  carelessly.
- **Destructive operations confirm first**, and the confirmation names what is about to happen.
- **Passwords are redacted from every log line.** The plugin logs failed requests, because that line
  is the most useful thing in a diagnosis — but logcat is readable with a cable.

---

## API facts that shaped the code

All read off `opentakserver/blueprints/ots_api/` on 2026-10-10, and all covered by tests.

**Creates take a JSON body; deletes take query parameters.** `POST /api/groups` reads
`request.json.get("name")`; `DELETE /api/groups` reads `request.args.get("group_name")` — a different
*place* and a different *field name* for the same idea. Sent the wrong way the server answers
"Missing group name", which reads like the name was wrong rather than absent from where it looked.

**And the exception:** `POST /api/user/delete` *does* take a body. Assuming symmetry with the group
deletes sends a `DELETE` to a route that does not accept one.

**Other shapes that bite:**

- `users` on `/api/groups/members` is a **list**, not a string.
- `direction` is **`IN` or `OUT`** and nothing else — not `BOTH`, which TAK convention would suggest.
- `/api/eud?all=1` and `/api/users/all` return a **bare array** with no envelope, unlike everything
  built by the server's `paginate()` helper. Code expecting `results` finds nothing there and reports
  an empty server.
- The auth token is `response.user.authentication_token`. One level up sits `response.csrf_token`,
  which is the **wrong one** — sending it authenticates as nobody and produces 401s that look exactly
  like a bad password.
- `include_auth_token` on `/api/login` is what makes the token appear at all.
- Refusals arrive as HTTP 400 with `{"success":false,"error":"…"}`. `HttpURLConnection` discards the
  body on a 4xx unless you read the error stream, so the plugin always does — without it every
  refusal becomes "HTTP 400".

**Server-side rules mirrored locally** (`OtsWriteGuard`), so a refusal arrives *before* a destructive
confirmation rather than after it:

- You cannot delete the account you are logged in as.
- `__ANON__` cannot be deleted.
- **LDAP mode blocks every user and group write.** The plugin cannot read `OTS_ENABLE_LDAP`, so it
  learns this from the first refusal and then stops offering the controls.
- Granting `administrator` requires `administrator`.

The server stays the authority: its own wording is always shown verbatim, because *"You can't delete
your own account"* explains itself better than anything substituted for it.

---

## Project structure

```
app/src/main/java/com/atakmap/android/otsadmin/
├── OtsRequest.java                # Every URL and body (unit tested)
├── OtsResponse.java               # The three envelopes + roles (unit tested)
├── OtsJson.java                   # Escaping and redaction (unit tested)
├── OtsWriteGuard.java             # The refusal rules (unit tested)
├── OtsSection.java                # What each list shows
├── OtsClient.java                 # HTTP, token header, error-stream reads
├── OtsSession.java                # Connect in three steps, roles, LDAP state
├── OtsAdminPlugin.java            # Pane, sections, write dialogs
└── OtsAdminPreferenceFragment.java
tools/IconGen.java                 # Java2D generator for both icons
```

---

## Licence

MIT. OpenTAKServer is GPL-3.0; this talks to its REST API and contains none of its code.
