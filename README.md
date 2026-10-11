# OTS Admin for ATAK

Administer an [OpenTAKServer](https://github.com/brian7704/OpenTAKServer) from inside ATAK: users,
groups and membership, plus read-only views of everything else its admin page shows.

**ATAK SDK:** 5.4.0 / 5.6.0 / 5.7.0 / 5.8.0 &nbsp;|&nbsp; **Min SDK:** 21 &nbsp;|&nbsp; **Version:** 0.9.0

> **Not yet run against a live server.** The API contract is implemented from OpenTAKServer's own
> blueprints and covered by 67 unit tests, but nothing here has made a real request. That is why it
> is still 0.x. Expect the first real connection to turn something up; start on a read-only section.

---

## Setting it up

**Use the same username and password you log into the web UI with.** There is no separate API
account, no API key, and nothing to generate. The plugin posts to `/api/login` — the same endpoint
the web UI's login form uses — and the only difference is the `include_auth_token` parameter, which
asks the server to hand back an API token alongside the session cookie. The web UI keeps the cookie;
the plugin sends the token in an `Authentication-Token` header instead.

Set the server URL, username and password either from the **Server** button on the plugin's own pane
or in **Settings → Tools → OTS Admin** — both write the same preference keys. Then press **Connect**.

### Token authentication is already on by default

The OpenTAKServer docs describe adding this to `~/ots/config.yml`:

```yaml
SECURITY_CSRF_PROTECT_MECHANISMS:
  - session
  - basic
```

**On current OpenTAKServer that is already the default** — `defaultconfig.py` sets exactly those two
values, alongside `WTF_CSRF_CHECK_DEFAULT = False` and `SECURITY_CSRF_IGNORE_UNAUTH_ENDPOINTS = True`.
So there is normally nothing to change; the documentation appears to predate the default.

It still matters for an older server, or a `config.yml` that overrides the setting (config.yml merges
over the defaults). In either, the login succeeds and the server returns **no API token**. The plugin
detects that specific case and says so, rather than reporting it as a bad password — which is what it
otherwise looks like.

> The OTS documentation is not a reliable guide to the current API. It also documents
> `/api/tcp/start`, `/api/tcp/stop` and `/api/ssl/start`, which **no longer exist** — the EUD handlers
> are separate processes now. Everything in this plugin is read off the blueprints instead.

---

## Three panes

| Pane | Size | What is on it |
|---|---|---|
| **Main** | small | Connection status, the account and its roles, **Connect**, **Server**, and a button per section. A trailing dot marks a section this plugin can change. A footer shows the build. |
| **Section** | full screen | One section's rows, with **‹ Back**. Rows are banded, because the detail fields wrap and without banding it is hard to see where one ends. |
| **Group** | full screen | One group's members, each with its own **Remove**, plus **Add members** and **Delete group**. |

Rows were originally inside the main pane. A row is a name plus four or five fields, and at pane
width that left two or three visible with every one wrapped — tabular data wants the screen, a
connection status does not.

---

## What it changes

| Section | Operations |
|---|---|
| **Users** | Add, delete, reset password, activate / deactivate, change roles |
| **Groups** | Add, delete, add members, remove a member |
| **Scheduled jobs** | Run now, pause, resume |
| **Server plugins** | Enable, disable |
| **Data packages** | Delete |
| **Device profiles** | Delete |

Per-user operations sit behind one **Manage** button on the row. Five buttons on a row leaves no
width for the name.

**Read-only:** devices (EUDs), certificates, video streams, missions, and server health. Each for a
reason — EUDs and certificates have no delete endpoint; video stream add/update is driven by a
WTForms config object taking arbitrary MediaMTX path settings, so there is no sensible dialog to
build without that field list; missions were not read.

### Group membership

Each group opens its own page, because the question is "who is in Bravo, and take Smith out of it" —
you cannot answer the first half without reading the membership, and typing a group name you are
already looking at is a way to misspell it.

**Add members shows a checklist of every account on the server**, from `GET /api/users/all`. Users
already in the group are marked with the direction they hold — `alice   (already IN)` — because
**adding an existing member silently succeeds and does nothing**: the server catches the integrity
error, rolls back, and still answers `success: true`. Without the mark, "Added 3 users" could be
fiction. They are marked rather than hidden, since a user can hold `IN` and `OUT` separately and
hiding them would make the second direction unreachable. A **Type instead** button and an automatic
fallback cover a server that will not list users.

**Direction is `IN` or `OUT`, and `BOTH` is two requests.** The server rejects anything that is not
exactly one of the two — but both is reachable, because `groups_users` is keyed on
**(user_id, group_id, direction)**, so one user can hold two rows for one group. Picking Both sends
two `PUT`s, stopping at the first failure and naming which step it was. The consequence is visible
and the dialog says so: that user then appears **twice** and needs removing **twice**.

**Direction belongs to the membership, not the group.** Remove uses the direction the server reported
for that row, rather than assuming `IN` — which would silently fail to remove an `OUT` membership.

---

## Security, stated plainly

- **The password is stored in ATAK's preferences, which are not encrypted.** A plugin has no secure
  store available to it: plugin code runs inside ATAK as ATAK's UID and cannot use its own app
  storage. A phone with this configured can delete every account on your server.
- **Use a dedicated administrator account for the plugin**, not your own. Losing the device then
  costs that one account, which you can delete from the web UI, instead of your own login.
- **There is no "trust all certificates" switch, and there will not be one.** Accepting any
  certificate offered is how an administrator's credentials end up with whoever controls the network.
  A server with a proper CA certificate works as-is; a self-signed one needs its certificate pinned,
  which is a feature worth writing properly rather than a flag worth adding carelessly.
- **Destructive operations confirm first**, and the confirmation names what is about to happen.
- **Passwords are redacted from every log line** — `password`, `confirm_password` and `new_password`.
  The plugin logs failed requests, because that line is the most useful thing in a diagnosis, but
  logcat is readable with a cable.
- **No shell access, and none is possible.** OpenTAKServer exposes no exec endpoint, no service
  restart and no config editing. The only route would be SSH from the plugin, which would mean shell
  credentials on a device whose preferences are not encrypted. Not built, and not wanted.

### What the server enforces, mirrored locally

`OtsWriteGuard` repeats these so a refusal arrives *before* a destructive confirmation rather than
after it. The server stays the authority and its own wording is always shown verbatim — *"You can't
delete your own account"* explains itself better than anything substituted for it.

- You cannot delete the account you are logged in as.
- `__ANON__` cannot be deleted.
- **LDAP mode blocks every user and group write.** The plugin cannot read `OTS_ENABLE_LDAP`, so it
  learns this from the first refusal and then stops offering the controls.
- Granting `administrator` requires `administrator`.
- A password reset needs **8 characters and no colon or at-sign**. That second rule is the server's
  and it is genuinely surprising — a password manager will suggest one containing either.
- Roles **replace** rather than add, so the dialog pre-ticks the current set and refuses an empty one.

One rule here is *not* a mirror: **deactivating your own account is blocked locally.** The server
allows it, unlike deleting your own account which it refuses outright — and then the next request
fails for no obvious reason.

---

## API facts that shaped the code

All read off `opentakserver/blueprints/ots_api/` on 2026-10-10, and all covered by tests.

**`/api/groups` carries three verbs.** `POST` creates, **`PUT` adds a member** (`add_user_to_group`),
`DELETE` removes the group. Member-add was first written as `POST /api/groups/members` — a route that
**does not exist**; that sub-resource has only `GET` and `DELETE`, so it returns 405 and reads like
the group name was wrong.

**Creates take a JSON body; deletes take query parameters.** And the field renames with the verb:
`name` on create, `group_name` on delete, `name` again on `GET /api/groups/members` — three spellings
on one resource.

**Except `POST /api/user/delete`, which does take a body.** It is a POST, not a DELETE, so assuming
symmetry with the group deletes sends a verb the route will not accept.

**Other shapes that bite:**

- `users` on member-add is a **list**, not a string.
- `/api/eud?all=1` and `/api/users/all` return a **bare array** with no envelope, unlike everything
  built by the server's `paginate()` helper (`results` / `total_pages` / `current_page` / `per_page` /
  `total`). Code expecting `results` finds nothing and reports an empty server.
- The auth token is `response.user.authentication_token`. One level up sits `response.csrf_token`,
  which is the **wrong one** — sending it authenticates as nobody and produces 401s that look exactly
  like a bad password.
- `activate` and `deactivate` are **separate endpoints**, not one with a flag.
- Refusals arrive as HTTP 400 with `{"success":false,"error":"…"}`. `HttpURLConnection` **discards the
  body on a 4xx** unless you read the error stream, so the plugin always does — without it every
  refusal becomes "HTTP 400".
- `POST /api/certificate` is only `@auth_required()`, **not** administrator-gated.

---

## Project structure

```
app/src/main/java/com/atakmap/android/otsadmin/
├── OtsRequest.java                # Every URL and body (unit tested)
├── OtsResponse.java               # The three envelopes + roles (unit tested)
├── OtsJson.java                   # Escaping and redaction (unit tested)
├── OtsWriteGuard.java             # The refusal rules (unit tested)
├── OtsSection.java                # What each list shows, and its labels
├── OtsClient.java                 # HTTP, token header, error-stream reads
├── OtsSession.java                # Connect in three steps, roles, LDAP state
├── OtsAdminPlugin.java            # Main pane, settings, build footer
├── OtsListPane.java               # Any section's rows, and the user writes
├── OtsGroupPane.java              # One group's members
└── OtsAdminPreferenceFragment.java
tools/IconGen.java                 # Java2D generator for both icons
```

Ten read-only sections are a descriptor each in `OtsSection` rendered by one pane, so the next view
is a line in an enum rather than a new file.

---

## Building

```
./gradlew.bat assembleCivDebug                       # 5.4, from local.properties
./gradlew.bat assembleCivDebug -Patak.version=5.6.0  # override for one build
```

`ATAK_VERSION` comes from `atak.version` in the gitignored `local.properties`, so each SDK tree
builds for its own host. Cloned into all four trees, all on `main` at the same commit.

**A fresh clone has no `gradle/wrapper/gradle-wrapper.jar`** — `.gitignore` excludes `*.jar`. Copy it
in after cloning; it stays untracked.

**The repo, the folder and the APK are spelled differently**, and that is fine: repo
`OTSAdmin-Plugin`, folder `OtsAdmin-Plugin`, package `com.atakmap.android.otsadmin`, APK
`ATAK-Plugin-OtsAdmin-Plugin-…`. `rootProject.name` is pinned in `settings.gradle`, so the APK name
does not follow the folder.

---

## Licence

MIT. OpenTAKServer is GPL-3.0; this talks to its REST API and contains none of its code.
