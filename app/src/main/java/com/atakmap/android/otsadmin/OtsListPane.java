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

        if (which.writable && session.isAdmin() && !session.isLdap()) {
            Button del = new Button(pluginContext);
            del.setText("Delete");
            del.setTextSize(10);
            del.setOnClickListener(v -> {
                if (which == OtsSection.USERS)
                    confirmDeleteUser(name);
                else
                    confirmDeleteGroup(name);
            });
            head.addView(del);
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
                detail.append("   ·   ");
            detail.append(field.replace('_', ' ')).append(": ")
                    .append(value);
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

    private void buildActionBar() {
        actionsBar.removeAllViews();
        boolean canWrite = section.writable && session != null
                && session.isReady() && session.isAdmin()
                && !session.isLdap();
        if (!canWrite) {
            actionsBar.setVisibility(View.GONE);
            return;
        }
        actionsBar.setVisibility(View.VISIBLE);
        if (section == OtsSection.USERS) {
            actionsBar.addView(action("Add user", this::promptAddUser));
        } else if (section == OtsSection.GROUPS) {
            actionsBar.addView(action("Add group", this::promptAddGroup));
            actionsBar.addView(action("Membership",
                    this::promptGroupMembership));
        }
    }

    private Button action(String label, Runnable onClick) {
        Button b = new Button(pluginContext);
        b.setText(label);
        b.setTextSize(11);
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

    private void confirmDeleteGroup(final String groupName) {
        String blocked = OtsWriteGuard.blockDeleteGroup(groupName);
        if (blocked != null) {
            say(blocked);
            return;
        }
        new AlertDialog.Builder(MapView.getMapView().getContext())
                .setTitle("Delete group " + groupName + "?")
                .setMessage("Members are not deleted, but they lose this"
                        + " channel.")
                .setPositiveButton("Delete", (d, w) -> write(
                        OtsRequest.deleteGroup(groupName),
                        "Deleted " + groupName))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void promptGroupMembership() {
        final EditText users = field("Usernames, comma separated", false);
        final EditText group = field("Group name", false);
        final EditText direction = field("Direction: IN or OUT", false);
        direction.setText(OtsWriteGuard.DIRECTION_IN);
        new AlertDialog.Builder(MapView.getMapView().getContext())
                .setTitle("Group membership")
                .setView(column(users, group, direction))
                .setPositiveButton("Add to group", (d, w) -> {
                    List<String> list = splitList(
                            users.getText().toString());
                    String dir = direction.getText().toString().trim()
                            .toUpperCase(Locale.US);
                    String blocked = OtsWriteGuard.blockGroupMembers(list,
                            group.getText().toString(), dir,
                            session.isLdap());
                    if (blocked != null) {
                        say(blocked);
                        return;
                    }
                    write(OtsRequest.addGroupMembers(list,
                            group.getText().toString().trim(), dir),
                            "Added to " + group.getText().toString().trim());
                })
                .setNeutralButton("Remove one", (d, w) -> {
                    List<String> list = splitList(
                            users.getText().toString());
                    String dir = direction.getText().toString().trim()
                            .toUpperCase(Locale.US);
                    if (list.size() != 1) {
                        // The server removes one at a time; saying so beats
                        // silently using the first of several.
                        say("Removing takes exactly one username.");
                        return;
                    }
                    write(OtsRequest.removeGroupMember(list.get(0),
                            group.getText().toString().trim(), dir),
                            "Removed " + list.get(0));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Send a write, report the server's own answer, then reload. */
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
