package com.atakmap.android.otsadmin;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.Pane;
import gov.tak.api.ui.PaneBuilder;

/**
 * One section's rows, on a page of its own.
 *
 * <h3>Why this is a second pane and not a panel on the first</h3>
 * It started as a list inside the main pane and there was not room for it. A
 * row here is a name plus four or five fields — a user's roles, an EUD's
 * team and when it last reported — and at the pane width that leaves two or
 * three rows visible with every one of them wrapped. Tabular data wants the
 * screen.
 *
 * So the main pane keeps what is small and always relevant (whether the
 * server is reachable, and which section to open), and this takes the full
 * display with a <b>‹ Back</b> to return. Same split as the weather plugin's
 * forecast page, for the same reason.
 *
 * It also owns the write dialogs, because every one of them acts on a row
 * that is shown here.
 */
public class OtsListPane {

    private static final String TAG = "OtsAdmin";

    private final Context pluginContext;
    private final IHostUIService uiService;
    private final OtsSession session;
    private final Runnable onBack;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newSingleThreadExecutor();

    private Pane pane;
    private TextView titleView, statusView;
    private LinearLayout actionsBar, rowsView;

    private OtsSection section = OtsSection.EUDS;

    /** Set by the plugin; opens one group's own page. */
    private GroupOpener groupOpener;

    /** How the list asks for a group to be opened. */
    public interface GroupOpener {
        void open(String groupName, String description);
    }

    public void setGroupOpener(GroupOpener opener) {
        this.groupOpener = opener;
    }

    public OtsListPane(Context pluginContext, IHostUIService uiService,
            OtsSession session, Runnable onBack) {
        this.pluginContext = pluginContext;
        this.uiService = uiService;
        this.session = session;
        this.onBack = onBack;
    }

    /** Open on a section and load it. */
    public void show(OtsSection which) {
        this.section = which;
        if (pane == null) {
            View root = PluginLayoutInflater.inflate(pluginContext,
                    R.layout.list_layout, null);
            titleView = root.findViewById(R.id.list_title);
            statusView = root.findViewById(R.id.list_status);
            actionsBar = root.findViewById(R.id.list_actions);
            rowsView = root.findViewById(R.id.list_rows);
            root.findViewById(R.id.list_back).setOnClickListener(v -> {
                if (onBack != null)
                    onBack.run();
            });
            root.findViewById(R.id.list_refresh)
                    .setOnClickListener(v -> load());
            // Full size: this is the page the data is actually read on.
            pane = new PaneBuilder(root)
                    .setMetaValue(Pane.RELATIVE_LOCATION,
                            Pane.Location.Default)
                    .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 1.0D)
                    .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 1.0D)
                    .build();
        }
        if (!uiService.isPaneVisible(pane))
            uiService.showPane(pane, null);
        load();
    }

    public void hide() {
        if (pane != null && uiService.isPaneVisible(pane))
            uiService.closePane(pane);
    }

    public void dispose() {
        exec.shutdownNow();
    }

    // ------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------

    private void load() {
        titleView.setText(section.label);
        buildActionBar();
        if (session == null || !session.isReady()) {
            rowsView.removeAllViews();
            statusView.setText("Not connected. Go back and press Connect.");
            return;
        }
        statusView.setText("Loading…");
        final OtsSection wanted = section;
        run(() -> {
            final OtsClient.Result r = session.client()
                    .send(wanted.request(1));
            ui.post(() -> {
                // The operator may have gone back or switched section while
                // this was in flight.
                if (wanted != section)
                    return;
                render(wanted, r);
            });
        });
    }

    private void render(OtsSection which, OtsClient.Result r) {
        rowsView.removeAllViews();
        if (!r.ok()) {
            String why = r.failure();
            if ((r.code == 403 || r.code == 401) && which.needsAdmin()
                    && !session.isAdmin())
                why = which.label + " needs the administrator role, and "
                        + session.username() + " does not have it.";
            statusView.setText(why);
            return;
        }
        try {
            OtsResponse.Page page = OtsResponse.page(r.body);
            statusView.setText(page.isEmpty()
                    ? "Nothing in " + which.label.toLowerCase(Locale.US)
                            + "."
                    : page.total + " total"
                            + (page.hasMore() ? " · showing the first "
                                    + page.results.size() : ""));
            boolean alt = false;
            for (JSONObject row : page.results) {
                rowsView.addView(rowView(which, row, alt));
                alt = !alt;
            }
        } catch (Exception e) {
            Log.w(TAG, "could not read " + which.label, e);
            statusView.setText("The server sent something unreadable.");
        }
    }

    /**
     * One row.
     *
     * Alternating backgrounds, because the fields wrap onto a second line
     * and without banding it is genuinely hard to see where one row ends.
     */
    private View rowView(final OtsSection which, final JSONObject row,
            boolean alt) {
        LinearLayout box = new LinearLayout(pluginContext);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(8, 10, 8, 10);
        if (alt)
            box.setBackgroundColor(0xFF1B2228);

        final String name = heading(which, row);

        LinearLayout head = new LinearLayout(pluginContext);
        head.setOrientation(LinearLayout.HORIZONTAL);
        TextView title = new TextView(pluginContext);
        title.setText(name);
        title.setTextSize(15);
        title.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams grow =
                new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        head.addView(title, grow);

        if (which == OtsSection.GROUPS) {
            // A group has something inside it, so the row opens rather than
            // offering a delete: who is in it is the thing you came to see,
            // and deleting it lives on that page next to its members.
            final String desc = readable(row, "description");
            Button open = new Button(pluginContext);
            open.setText("Members \u203a");
            open.setTextSize(10);
            open.setOnClickListener(v -> {
                if (groupOpener != null)
                    groupOpener.open(name, desc);
            });
            head.addView(open);
            box.setOnClickListener(v -> {
                if (groupOpener != null)
                    groupOpener.open(name, desc);
            });
        } else if (which.writable && canChange(which)) {
            addRowActions(head, which, row, name);
        }
        box.addView(head);

        StringBuilder detail = new StringBuilder();
        for (String field : which.fields()) {
            if (field.equals(which.titleField()))
                continue;
            String value = readable(row, field);
            if (value.isEmpty())
                continue;
            if (detail.length() > 0)
                detail.append("  ·  ");
            String label = OtsSection.labelFor(field);
            if (!label.isEmpty())
                detail.append(label).append(' ');
            detail.append(value);
        }
        if (detail.length() > 0) {
            TextView sub = new TextView(pluginContext);
            sub.setText(detail.toString());
            sub.setTextSize(12);
            sub.setTextColor(0xFFB0BEC5);
            box.addView(sub);
        }
        return box;
    }

    /**
     * The buttons a row gets, by section.
     *
     * Users get one "Manage" button rather than five, because five on a row
     * leaves no width for the name. Everything else has few enough actions
     * to show them directly.
     */
    private void addRowActions(LinearLayout head, OtsSection which,
            JSONObject row, String name) {
        if (which == OtsSection.USERS) {
            head.addView(smallButton("Groups",
                    () -> showUserGroups(name)));
            head.addView(smallButton("Manage",
                    () -> promptManageUser(row, name)));
        } else if (which == OtsSection.SCHEDULER) {
            // A paused job reports no next run time, which is how the
            // buttons know which way round to offer themselves.
            boolean paused = readable(row, "next_run_time").isEmpty();
            head.addView(smallButton("Run now",
                    () -> write(OtsRequest.runJob(name),
                            "Started " + name)));
            head.addView(smallButton(paused ? "Resume" : "Pause",
                    () -> write(paused ? OtsRequest.resumeJob(name)
                            : OtsRequest.pauseJob(name),
                            (paused ? "Resumed " : "Paused ") + name)));
        } else if (which == OtsSection.PLUGINS) {
            boolean enabled = row.optBoolean("enabled", false);
            head.addView(smallButton(enabled ? "Disable" : "Enable",
                    () -> confirmPlugin(name, enabled)));
        } else if (which == OtsSection.DATA_PACKAGES) {
            final String hash = readable(row, "hash");
            head.addView(smallButton("Delete",
                    () -> confirmSimpleDelete("data package " + name,
                            OtsRequest.deleteDataPackage(hash),
                            hash.isEmpty()
                                    ? "This package has no hash, which is"
                                            + " what a delete is keyed on."
                                    : null)));
        } else if (which == OtsSection.PROFILES) {
            final String uid = readable(row, "eud_uid");
            final String key = readable(row, "preference_key");
            head.addView(smallButton("Delete",
                    () -> confirmSimpleDelete("preference " + key,
                            OtsRequest.deleteDeviceProfile(uid, key),
                            key.isEmpty()
                                    ? "This row has no preference key,"
                                            + " which a delete needs."
                                    : null)));
        }
    }

    /**
     * A row button: small, lower case, and no wider than its text.
     *
     * The default Android button in a plugin context is chunky and
     * upper-cases its label, which on a row leaves little width for the
     * thing the row is about.
     */
    private Button smallButton(String label, Runnable onClick) {
        Button b = new Button(pluginContext);
        b.setText(label);
        b.setTextSize(10);
        b.setAllCaps(false);
        b.setPadding(14, 2, 14, 2);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setOnClickListener(v -> onClick.run());
        return b;
    }

    /** Everything that can be done to one account, in one list. */
    private void promptManageUser(final JSONObject row,
            final String username) {
        boolean active = row.optBoolean("active", true);
        final String[] labels = {
                "Reset password",
                active ? "Deactivate" : "Activate",
                "Change roles",
                "Delete user" };
        new AlertDialog.Builder(MapView.getMapView().getContext())
                .setTitle(username)
                .setItems(labels, (d, which) -> {
                    switch (which) {
                        case 0:
                            promptResetPassword(username);
                            break;
                        case 1:
                            toggleActive(username, active);
                            break;
                        case 2:
                            promptRoles(row, username);
                            break;
                        default:
                            confirmDeleteUser(username);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void promptResetPassword(final String username) {
        final EditText pass = field("New password", true);
        final EditText confirm = field("Confirm", true);
        TextView note = new TextView(pluginContext);
        note.setText("At least " + OtsWriteGuard.MIN_PASSWORD_LENGTH
                + " characters, and the server rejects a colon or an"
                + " at-sign.");
        note.setTextSize(10);
        note.setTextColor(0xFF78909C);
        new AlertDialog.Builder(MapView.getMapView().getContext())
                .setTitle("Reset " + username + "'s password")
                .setView(column(pass, confirm, note))
                .setPositiveButton("Reset", (d, w) -> {
                    String blocked = OtsWriteGuard.blockResetPassword(
                            username, pass.getText().toString(),
                            confirm.getText().toString(),
                            session.isLdap());
                    if (blocked != null) {
                        say(blocked);
                        return;
                    }
                    write(OtsRequest.resetPassword(username,
                            pass.getText().toString()),
                            "Reset the password for " + username);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void toggleActive(final String username, boolean active) {
        if (!active) {
            write(OtsRequest.activateUser(username),
                    "Activated " + username);
            return;
        }
        String blocked = OtsWriteGuard.blockDeactivate(username,
                session.username(), session.isLdap());
        if (blocked != null) {
            say(blocked);
            return;
        }
        new AlertDialog.Builder(MapView.getMapView().getContext())
                .setTitle("Deactivate " + username + "?")
                .setMessage("The account stops working immediately and its"
                        + " devices lose access. It is not deleted, and this"
                        + " can be undone.")
                .setPositiveButton("Deactivate", (d, w) -> write(
                        OtsRequest.deactivateUser(username),
                        "Deactivated " + username))
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Roles REPLACE rather than add, so the current set is pre-ticked.
     *
     * Without that, opening this and pressing Save would quietly strip every
     * role the account already had.
     */
    private void promptRoles(final JSONObject row, final String username) {
        final String[] choices = { OtsWriteGuard.ROLE_USER,
                OtsWriteGuard.ROLE_ADMIN };
        String current = readable(row, "roles");
        final boolean[] picked = new boolean[choices.length];
        for (int i = 0; i < choices.length; i++)
            picked[i] = current.contains(choices[i]);
        new AlertDialog.Builder(MapView.getMapView().getContext())
                .setTitle(username + "'s roles")
                .setMultiChoiceItems(choices, picked,
                        (d, which, isChecked) -> picked[which] = isChecked)
                .setPositiveButton("Save", (d, w) -> {
                    List<String> roles = new ArrayList<>();
                    for (int i = 0; i < choices.length; i++)
                        if (picked[i])
                            roles.add(choices[i]);
                    String blocked = OtsWriteGuard.blockSetRoles(username,
                            roles, session.isAdmin(), session.isLdap());
                    if (blocked != null) {
                        say(blocked);
                        return;
                    }
                    write(OtsRequest.setUserRoles(username, roles),
                            "Set " + username + " to " + roles);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmPlugin(final String name, final boolean enabled) {
        new AlertDialog.Builder(MapView.getMapView().getContext())
                .setTitle((enabled ? "Disable " : "Enable ") + name + "?")
                .setMessage("This is a plugin on the SERVER, not on this"
                        + " device. It may need an OpenTAKServer restart to"
                        + " take effect.")
                .setPositiveButton(enabled ? "Disable" : "Enable",
                        (d, w) -> write(enabled
                                ? OtsRequest.disableServerPlugin(name)
                                : OtsRequest.enableServerPlugin(name),
                                (enabled ? "Disabled " : "Enabled ")
                                        + name))
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** A delete with nothing to fill in, and a reason it cannot run. */
    private void confirmSimpleDelete(final String what,
            final OtsRequest request, final String impossible) {
        if (impossible != null) {
            say(impossible);
            return;
        }
        new AlertDialog.Builder(MapView.getMapView().getContext())
                .setTitle("Delete " + what + "?")
                .setMessage("This cannot be undone from here.")
                .setPositiveButton("Delete", (d, w) -> write(request,
                        "Deleted " + what))
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Tolerates the server's two spellings of a group's name. */
    private static String heading(OtsSection which, JSONObject row) {
        String name = readable(row, which.titleField());
        if (name.isEmpty() && which == OtsSection.GROUPS)
            name = readable(row, "group_name");
        return name.isEmpty() ? "(unnamed)" : name;
    }

    /**
     * A field as something to put on screen.
     *
     * Arrays are flattened, because roles arrive as one and a bare
     * {@code toString} renders with its brackets. JSON null comes back from
     * {@code optString} as the literal "null", which must read as absent
     * rather than as a value.
     */
    private static String readable(JSONObject row, String field) {
        if (row == null || !row.has(field) || row.isNull(field))
            return "";
        JSONArray arr = row.optJSONArray(field);
        if (arr != null) {
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                String v = o != null ? o.optString("name", "")
                        : arr.optString(i, "");
                if (!v.isEmpty())
                    parts.add(v);
            }
            return join(parts);
        }
        String v = row.optString(field, "");
        return "null".equals(v) ? "" : v;
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0)
                sb.append(", ");
            sb.append(p);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    /**
     * Whether this plugin may change this section.
     *
     * Gated on the administrator role only where the SERVER gates it.
     * Scheduler jobs and data packages are auth_required and nothing more,
     * so demanding admin for them would withhold something the server would
     * happily allow. LDAP only owns accounts and group membership, so it
     * does not block the rest either.
     */
    private boolean canChange(OtsSection which) {
        if (session == null || !session.isReady())
            return false;
        if (which.needsAdmin() && !session.isAdmin())
            return false;
        boolean accountish = which == OtsSection.USERS
                || which == OtsSection.GROUPS;
        return !accountish
                || (session.isAdmin() && !session.isLdap());
    }

    private void buildActionBar() {
        actionsBar.removeAllViews();
        boolean canWrite = section.writable && canChange(section);
        if (!canWrite) {
            actionsBar.setVisibility(View.GONE);
            return;
        }
        actionsBar.setVisibility(View.VISIBLE);
        if (section == OtsSection.USERS) {
            actionsBar.addView(action("Add user", this::promptAddUser));
        } else if (section == OtsSection.GROUPS) {
            // Membership and delete now live on each group's own page,
            // where the group is already known and its members are visible.
            actionsBar.addView(action("Add group", this::promptAddGroup));
        }
    }

    private Button action(String label, Runnable onClick) {
        Button b = new Button(pluginContext);
        b.setText(label);
        b.setTextSize(11);
        b.setAllCaps(false);
        b.setOnClickListener(v -> onClick.run());
        return b;
    }

    private void promptAddUser() {
        final EditText user = field("Username", false);
        final EditText pass = field("Password", true);
        final EditText confirm = field("Confirm password", true);
        final EditText roles = field("Roles, comma separated (blank = user)",
                false);
        new AlertDialog.Builder(MapView.getMapView().getContext())
                .setTitle("Add a user")
                .setView(column(user, pass, confirm, roles))
                .setPositiveButton("Add", (d, w) -> {
                    List<String> roleList = splitList(
                            roles.getText().toString());
                    String blocked = OtsWriteGuard.blockAddUser(
                            user.getText().toString(),
                            pass.getText().toString(),
                            confirm.getText().toString(), roleList,
                            session.isAdmin(), session.isLdap());
                    if (blocked != null) {
                        say(blocked);
                        return;
                    }
                    write(OtsRequest.addUser(
                            user.getText().toString().trim(),
                            pass.getText().toString(), roleList),
                            "Added " + user.getText().toString().trim());
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmDeleteUser(final String username) {
        String blocked = OtsWriteGuard.blockDeleteUser(username,
                session.username(), session.isLdap());
        if (blocked != null) {
            say(blocked);
            return;
        }
        new AlertDialog.Builder(MapView.getMapView().getContext())
                .setTitle("Delete " + username + "?")
                .setMessage("This removes the account on the server. It"
                        + " cannot be undone from here.")
                .setPositiveButton("Delete", (d, w) -> write(
                        OtsRequest.deleteUser(username),
                        "Deleted " + username))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void promptAddGroup() {
        final EditText name = field("Group name", false);
        final EditText desc = field("Description (optional)", false);
        new AlertDialog.Builder(MapView.getMapView().getContext())
                .setTitle("Add a group")
                .setView(column(name, desc))
                .setPositiveButton("Add", (d, w) -> {
                    String blocked = OtsWriteGuard.blockAddGroup(
                            name.getText().toString());
                    if (blocked != null) {
                        say(blocked);
                        return;
                    }
                    write(OtsRequest.addGroup(
                            name.getText().toString().trim(),
                            desc.getText().toString().trim()),
                            "Added " + name.getText().toString().trim());
                })
                .setNegativeButton("Cancel", null)
                .show();
    }



    /** Send a write, report the server's own answer, then reload. */
    /**
     * Which groups one account belongs to.
     *
     * The reverse of the per-group member list in OtsGroupPane, from
     * GET /api/users/groups. Read-only on purpose: membership is changed from
     * the group side, where the direction being added is already in context.
     *
     * Three answers get distinct wording, because they mean different things
     * to whoever is looking:
     *   - no memberships at all,
     *   - the account does not exist (the server answers 404), and
     *   - LDAP is in charge, so the server will not answer at all (400).
     * Collapsing those into "nothing found" would have an operator hunting a
     * missing group when the real answer is a typo or LDAP.
     */
    private void showUserGroups(final String username) {
        statusView.setText("Reading groups for " + username + "\u2026");
        run(() -> {
            final OtsClient.Result r =
                    session.client().send(OtsRequest.userGroups(username));
            ui.post(() -> {
                String failure = r.failure();
                if (failure != null) {
                    if (OtsWriteGuard.looksLikeLdapRefusal(failure))
                        session.noteLdapRefusal();
                    say(failure);
                    return;
                }
                List<String> lines = new ArrayList<>();
                try {
                    OtsResponse.Page page = OtsResponse.page(r.body);
                    for (JSONObject row : page.results) {
                        // group_name here, though the same field is "name" on
                        // /api/groups/members and on create. Read by name.
                        String group = readable(row, "group_name");
                        if (group.isEmpty())
                            group = readable(row, "name");
                        String direction = readable(row, "direction");
                        // "active" in this response; the column behind it is
                        // called "enabled".
                        boolean active = row.optBoolean("active", true);
                        StringBuilder line = new StringBuilder();
                        line.append(group.isEmpty() ? "(unnamed group)" : group);
                        if (!direction.isEmpty())
                            line.append("  \u00b7  ").append(direction);
                        if (!active)
                            line.append("  \u00b7  inactive");
                        lines.add(line.toString());
                    }
                } catch (Exception e) {
                    say("the group list could not be read");
                    return;
                }

                String body;
                if (lines.isEmpty()) {
                    body = username + " belongs to no groups.";
                } else {
                    StringBuilder sb = new StringBuilder();
                    for (String line : lines)
                        sb.append("\u2022  ").append(line).append('\n');
                    // A user can appear twice for one group, once per
                    // direction, because the membership table is keyed on
                    // (user, group, direction) -- so two rows naming the same
                    // group is correct, not a duplicate to be filtered.
                    sb.append('\n').append(lines.size())
                      .append(lines.size() == 1 ? " membership" : " memberships");
                    body = sb.toString();
                }
                statusView.setText("");
                new AlertDialog.Builder(MapView.getMapView().getContext())
                        .setTitle("Groups for " + username)
                        .setMessage(body)
                        .setPositiveButton("Close", null)
                        .show();
            });
        });
    }

    private void write(final OtsRequest request, final String successText) {
        statusView.setText("Working…");
        run(() -> {
            final OtsClient.Result r = session.client().send(request);
            ui.post(() -> {
                String failure = r.failure();
                if (failure == null && OtsResponse.succeeded(r.body)) {
                    say(successText);
                } else {
                    String why = failure != null ? failure
                            : "the server did not confirm it";
                    if (OtsWriteGuard.looksLikeLdapRefusal(why))
                        session.noteLdapRefusal();
                    say(why);
                }
                load();
            });
        });
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private EditText field(String hint, boolean password) {
        EditText e = new EditText(pluginContext);
        e.setHint(hint);
        e.setTextSize(14);
        e.setSingleLine(true);
        if (password)
            e.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return e;
    }

    private LinearLayout column(View... views) {
        LinearLayout box = new LinearLayout(pluginContext);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(24, 12, 24, 12);
        for (View v : views)
            box.addView(v);
        return box;
    }

    private static List<String> splitList(String text) {
        List<String> out = new ArrayList<>();
        if (text == null)
            return out;
        for (String part : text.split(",")) {
            String p = part.trim();
            if (!p.isEmpty())
                out.add(p);
        }
        return out;
    }

    /**
     * The server's answer, on screen.
     *
     * A dialog as well as the status line: these are the outcomes of
     * destructive operations and the wording carries the reason, which is
     * worth having to dismiss. Built with a resolved String, never an
     * R.string int — an int handed to a dialog on ATAK's context resolves
     * against ATAK's resources.
     */
    private void say(String text) {
        statusView.setText(text);
        try {
            new AlertDialog.Builder(MapView.getMapView().getContext())
                    .setMessage(text)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        } catch (Exception e) {
            Log.w(TAG, "could not show a dialog", e);
        }
    }

    private void run(Runnable r) {
        try {
            exec.execute(r);
        } catch (Exception e) {
            Log.w(TAG, "rejected after shutdown", e);
        }
    }
}
