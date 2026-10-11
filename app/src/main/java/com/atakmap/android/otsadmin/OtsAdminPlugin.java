package com.atakmap.android.otsadmin;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.atak.plugins.impl.PluginContextProvider;
import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.maps.MapView;
import com.atakmap.app.preferences.ToolsPreferenceFragment;
import com.atakmap.coremap.log.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.Pane;
import gov.tak.api.ui.PaneBuilder;
import gov.tak.api.ui.ToolbarItem;
import gov.tak.api.ui.ToolbarItemAdapter;
import gov.tak.platform.marshal.MarshalManager;

/**
 * Administer an OpenTAKServer from inside ATAK.
 *
 * <h3>What it changes, and what it only shows</h3>
 * Writes are limited to <b>users and groups</b> — add, delete, and group
 * membership. Everything else is read-only on purpose: those are the
 * operations worth doing from a phone, and every extra write is another way
 * for a mistaken tap on a device in a pocket to cost something.
 *
 * <h3>Dialog resource ids go through the PLUGIN context</h3>
 * Every {@code setTitle}/{@code setMessage}/{@code setButton} below passes a
 * resolved String, never an {@code R.string} int. An int id handed to a
 * dialog built on ATAK's context is resolved against <b>ATAK's</b> resource
 * table, so the number silently names some unrelated ATAK resource — it
 * compiles, and only shows up on device as a button labelled after a drawable.
 * Documented trap in this portfolio, hit twice before.
 */
public class OtsAdminPlugin implements IPlugin {

    private static final String TAG = "OtsAdmin";
    public static final String PREFS_KEY = "OTS Admin Preferences";

    private IServiceController serviceController;
    private IHostUIService uiService;
    private ToolbarItem toolbarItem;
    private Context pluginContext;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newSingleThreadExecutor();

    private Pane pane;
    private TextView statusView, rolesView, sectionStatusView;
    private LinearLayout sectionsBar, actionsBar, listView;
    private Button connectButton;

    private OtsSession session;
    private OtsSection current = OtsSection.EUDS;

    public OtsAdminPlugin() {
    }

    public OtsAdminPlugin(IServiceController serviceController) {
        try {
            this.serviceController = serviceController;
            final PluginContextProvider ctxProvider =
                    serviceController.getService(PluginContextProvider.class);
            if (ctxProvider != null) {
                pluginContext = ctxProvider.getPluginContext();
                pluginContext.setTheme(R.style.AppTheme);
            }
            uiService = serviceController.getService(IHostUIService.class);

            toolbarItem = new ToolbarItem.Builder(
                    pluginContext.getString(R.string.app_name),
                    MarshalManager.marshal(
                            pluginContext.getResources()
                                    .getDrawable(R.drawable.ic_toolbar),
                            android.graphics.drawable.Drawable.class,
                            gov.tak.api.commons.graphics.Bitmap.class))
                    .setListener(new ToolbarItemAdapter() {
                        @Override
                        public void onClick(ToolbarItem item) {
                            showPane();
                        }
                    }).build();
            registerPreferences();
        } catch (Exception e) {
            Log.e(TAG, "Failed to construct plugin", e);
        }
    }

    /**
     * Put the settings under ATAK Settings > Tools.
     *
     * Registration has to happen on the main thread; the constructor is not
     * guaranteed to be on it, so this hops when it needs to.
     */
    private void registerPreferences() {
        if (Looper.myLooper() != Looper.getMainLooper())
            new Handler(Looper.getMainLooper())
                    .post(this::registerPreferencesInternal);
        else
            registerPreferencesInternal();
    }

    private void registerPreferencesInternal() {
        try {
            try {
                ToolsPreferenceFragment.unregister(PREFS_KEY);
            } catch (Exception ignored) {
                // Nothing registered yet, which is the normal first run.
            }
            ToolsPreferenceFragment.register(
                    new ToolsPreferenceFragment.ToolPreference(
                            PREFS_KEY,
                            pluginContext.getString(
                                    R.string.preferences_summary),
                            PREFS_KEY,
                            pluginContext.getResources()
                                    .getDrawable(R.drawable.ic_launcher),
                            new OtsAdminPreferenceFragment(pluginContext)));
        } catch (Exception e) {
            Log.e(TAG, "Failed to register preferences", e);
        }
    }

    @Override
    public void onStart() {
        if (uiService == null)
            return;
        uiService.addToolbarItem(toolbarItem);
        SharedPreferences prefs = PreferenceManager
                .getDefaultSharedPreferences(
                        MapView.getMapView().getContext());
        session = new OtsSession(prefs);
    }

    @Override
    public void onStop() {
        if (uiService != null && toolbarItem != null)
            uiService.removeToolbarItem(toolbarItem);
        exec.shutdownNow();
        if (session != null)
            session.disconnect();
    }

    // ------------------------------------------------------------------
    // Pane
    // ------------------------------------------------------------------

    private void showPane() {
        if (pane == null) {
            View root = PluginLayoutInflater.inflate(pluginContext,
                    R.layout.main_layout, null);
            statusView = root.findViewById(R.id.ots_status);
            rolesView = root.findViewById(R.id.ots_roles);
            sectionStatusView = root.findViewById(R.id.ots_section_status);
            sectionsBar = root.findViewById(R.id.ots_sections);
            actionsBar = root.findViewById(R.id.ots_actions);
            listView = root.findViewById(R.id.ots_list);
            connectButton = root.findViewById(R.id.ots_connect);
            connectButton.setOnClickListener(v -> connect());
            root.findViewById(R.id.ots_refresh)
                    .setOnClickListener(v -> loadSection());
            buildSectionBar();
            pane = new PaneBuilder(root)
                    .setMetaValue(Pane.RELATIVE_LOCATION,
                            Pane.Location.Default)
                    .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.5D)
                    .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.6D)
                    .build();
        }
        if (!uiService.isPaneVisible(pane))
            uiService.showPane(pane, null);
        renderSession();
        if (session != null && !session.isReady())
            connect();
        else
            loadSection();
    }

    /** One button per section, straight from the enum. */
    private void buildSectionBar() {
        sectionsBar.removeAllViews();
        for (final OtsSection s : OtsSection.values()) {
            Button b = new Button(pluginContext);
            b.setText(s.label);
            b.setTextSize(11);
            b.setOnClickListener(v -> {
                current = s;
                loadSection();
            });
            sectionsBar.addView(b);
        }
    }

    private void renderSession() {
        if (session == null)
            return;
        statusView.setText(session.message());
        boolean ready = session.isReady();
        statusView.setTextColor(ready ? 0xFF81C784
                : session.state() == OtsSession.State.CONNECTING
                        ? 0xFFFFB74D : 0xFFFF5252);
        connectButton.setText(ready ? "Reconnect" : "Connect");
        if (ready) {
            rolesView.setVisibility(View.VISIBLE);
            String roles = session.roles().isEmpty() ? "no roles reported"
                    : "roles: " + join(session.roles());
            rolesView.setText(session.isLdap()
                    ? roles + " · LDAP owns accounts, so user and group"
                            + " changes are disabled"
                    : roles);
        } else {
            rolesView.setVisibility(View.GONE);
        }
    }

    private void connect() {
        if (session == null)
            return;
        statusView.setText("Connecting…");
        statusView.setTextColor(0xFFFFB74D);
        run(() -> {
            session.connect();
            ui.post(() -> {
                renderSession();
                if (session.isReady())
                    loadSection();
            });
        });
    }

    // ------------------------------------------------------------------
    // Lists
    // ------------------------------------------------------------------

    private void loadSection() {
        if (session == null || !session.isReady()) {
            listView.removeAllViews();
            sectionStatusView.setText(
                    "Connect to the server to see " + current.label + ".");
            actionsBar.setVisibility(View.GONE);
            return;
        }
        buildActionBar();
        sectionStatusView.setText("Loading " + current.label + "…");
        final OtsSection section = current;
        run(() -> {
            final OtsClient.Result r = session.client()
                    .send(section.request(1));
            ui.post(() -> {
                if (section != current)
                    return;   // the operator moved on while it was loading
                renderList(section, r);
            });
        });
    }

    private void renderList(OtsSection section, OtsClient.Result r) {
        listView.removeAllViews();
        if (!r.ok()) {
            String why = r.failure();
            // 403 on an administrator-only section is worth naming as such
            // rather than leaving as a bare code.
            if ((r.code == 403 || r.code == 401) && section.needsAdmin()
                    && !session.isAdmin())
                why = section.label + " needs the administrator role, and "
                        + session.username() + " does not have it.";
            sectionStatusView.setText(why);
            return;
        }
        try {
            OtsResponse.Page page = OtsResponse.page(r.body);
            sectionStatusView.setText(page.isEmpty()
                    ? "Nothing in " + section.label + "."
                    : page.total + " " + section.label.toLowerCase()
                            + (page.hasMore()
                                    ? " · showing the first "
                                            + page.results.size() : ""));
            for (JSONObject row : page.results)
                listView.addView(rowView(section, row));
        } catch (Exception e) {
            Log.w(TAG, "could not read " + section.label, e);
            sectionStatusView.setText(
                    "The server sent something unreadable for "
                            + section.label + ".");
        }
    }

    /** A row: the title field bold, then whichever other fields exist. */
    private View rowView(final OtsSection section, final JSONObject row) {
        LinearLayout box = new LinearLayout(pluginContext);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, 8, 0, 8);

        TextView title = new TextView(pluginContext);
        String name = heading(section, row);
        title.setText(name);
        title.setTextSize(14);
        title.setTextColor(0xFFFFFFFF);
        box.addView(title);

        StringBuilder detail = new StringBuilder();
        for (String field : section.fields()) {
            if (field.equals(section.titleField()))
                continue;
            String value = readable(row, field);
            if (value.isEmpty())
                continue;
            if (detail.length() > 0)
                detail.append("  ·  ");
            detail.append(field.replace('_', ' ')).append(": ")
                    .append(value);
        }
        if (detail.length() > 0) {
            TextView sub = new TextView(pluginContext);
            sub.setText(detail.toString());
            sub.setTextSize(11);
            sub.setTextColor(0xFFB0BEC5);
            box.addView(sub);
        }

        // Per-row deletes, where this plugin writes at all.
        if (section.writable && session.isAdmin() && !session.isLdap()) {
            Button del = new Button(pluginContext);
            del.setText(section == OtsSection.USERS ? "Delete user"
                    : "Delete group");
            del.setTextSize(10);
            del.setOnClickListener(v -> {
                if (section == OtsSection.USERS)
                    confirmDeleteUser(name);
                else
                    confirmDeleteGroup(name);
            });
            box.addView(del);
        }
        return box;
    }

    /** The row heading, tolerating the server's two spellings for groups. */
    private static String heading(OtsSection section, JSONObject row) {
        String name = readable(row, section.titleField());
        if (name.isEmpty() && section == OtsSection.GROUPS)
            name = readable(row, "group_name");
        return name.isEmpty() ? "(unnamed)" : name;
    }

    /**
     * A field as something to put on screen.
     *
     * Arrays are flattened because roles arrive as one, and a bare
     * {@code toString} would render as {@code ["user"]} with the brackets.
     * JSON null arrives as the string "null" from optString, which must read
     * as absent rather than as a value.
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
        boolean canWrite = current.writable && session.isAdmin()
                && !session.isLdap();
        if (!canWrite) {
            actionsBar.setVisibility(View.GONE);
            return;
        }
        actionsBar.setVisibility(View.VISIBLE);
        if (current == OtsSection.USERS) {
            actionsBar.addView(action("Add user", this::promptAddUser));
        } else if (current == OtsSection.GROUPS) {
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
        LinearLayout box = column(user, pass, confirm, roles);
        new AlertDialog.Builder(MapView.getMapView().getContext())
                .setTitle("Add a user")
                .setView(box)
                .setPositiveButton("Add", (d, w) -> {
                    List<String> roleList = splitRoles(
                            roles.getText().toString());
                    String blocked = OtsWriteGuard.blockAddUser(
                            user.getText().toString(),
                            pass.getText().toString(),
                            confirm.getText().toString(), roleList,
                            session.isAdmin(), session.isLdap());
                    if (blocked != null) {
                        toast(blocked);
                        return;
                    }
                    write(OtsRequest.addUser(user.getText().toString().trim(),
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
            toast(blocked);
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
                        toast(blocked);
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
            toast(blocked);
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
                    List<String> list = splitRoles(
                            users.getText().toString());
                    String dir = direction.getText().toString()
                            .trim().toUpperCase(java.util.Locale.US);
                    String blocked = OtsWriteGuard.blockGroupMembers(list,
                            group.getText().toString(), dir,
                            session.isLdap());
                    if (blocked != null) {
                        toast(blocked);
                        return;
                    }
                    write(OtsRequest.addGroupMembers(list,
                            group.getText().toString().trim(), dir),
                            "Added to " + group.getText().toString().trim());
                })
                .setNeutralButton("Remove one", (d, w) -> {
                    List<String> list = splitRoles(
                            users.getText().toString());
                    String dir = direction.getText().toString()
                            .trim().toUpperCase(java.util.Locale.US);
                    if (list.size() != 1) {
                        // The server removes one at a time; saying so beats
                        // silently using the first of several.
                        toast("Removing takes exactly one username.");
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
        sectionStatusView.setText("Working…");
        run(() -> {
            final OtsClient.Result r = session.client().send(request);
            ui.post(() -> {
                String failure = r.failure();
                if (failure == null && OtsResponse.succeeded(r.body)) {
                    toast(successText);
                } else {
                    String why = failure != null ? failure
                            : "the server did not confirm it";
                    if (OtsWriteGuard.looksLikeLdapRefusal(why)) {
                        session.noteLdapRefusal();
                        renderSession();
                    }
                    toast(why);
                }
                loadSection();
            });
        });
    }

    // ------------------------------------------------------------------
    // Small UI helpers
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

    private static List<String> splitRoles(String text) {
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
     * A dialog rather than a toast: these are the outcomes of destructive
     * operations and the wording carries the reason, which is worth having
     * to dismiss. Built on ATAK's context with a resolved String — never an
     * R.string int, see the class note.
     */
    private void toast(String text) {
        sectionStatusView.setText(text);
        try {
            new AlertDialog.Builder(MapView.getMapView().getContext())
                    .setMessage(text)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        } catch (Exception e) {
            // The status line already has it.
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
