package com.atakmap.android.otsadmin;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.Pane;
import gov.tak.api.ui.PaneBuilder;

/**
 * One group, on its own page: who is in it, and changing that.
 *
 * <h3>Why this exists</h3>
 * Membership started as a single dialog on the groups list where you typed
 * the group name, the usernames and the direction. That works once you know
 * all three, and it is the wrong shape for the actual job — which is "who is
 * in Bravo, and take Smith out of it". You cannot answer the first half at
 * all without being able to read the membership, and typing a group name you
 * are already looking at is a way to misspell it.
 *
 * So each group opens here: its members listed, each with a Remove, and an
 * Add that already knows which group it is adding to.
 *
 * <h3>Two server shapes worth knowing</h3>
 * <ul>
 *   <li>{@code GET /api/groups/members?name=<group>} returns a <b>bare
 *       array</b>, not a paginated envelope, of
 *       {@code {username, direction, active}}. The query field is
 *       {@code name} here, while removal wants {@code group_name} — a third
 *       spelling on the same resource.</li>
 *   <li><b>Direction is part of the membership, not of the group.</b> The
 *       same user can be in one group IN and another OUT, and removal needs
 *       the direction as well as the name — so Remove uses the direction the
 *       server reported for that row rather than asking again or assuming
 *       IN.</li>
 * </ul>
 */
public class OtsGroupPane {

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

    private String groupName = "";
    private String description = "";

    public OtsGroupPane(Context pluginContext, IHostUIService uiService,
            OtsSession session, Runnable onBack) {
        this.pluginContext = pluginContext;
        this.uiService = uiService;
        this.session = session;
        this.onBack = onBack;
    }

    /** Open on one group. */
    public void show(String name, String groupDescription) {
        this.groupName = name == null ? "" : name;
        this.description = groupDescription == null ? "" : groupDescription;
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
    // Members
    // ------------------------------------------------------------------

    private void load() {
        titleView.setText(description.isEmpty() ? groupName
                : groupName + "  —  " + description);
        buildActionBar();
        if (session == null || !session.isReady()) {
            rowsView.removeAllViews();
            statusView.setText("Not connected.");
            return;
        }
        statusView.setText("Loading members…");
        final String wanted = groupName;
        run(() -> {
            final OtsClient.Result r = session.client()
                    .send(OtsRequest.groupMembers(wanted));
            ui.post(() -> {
                if (!wanted.equals(groupName))
                    return;
                render(r);
            });
        });
    }

    private void render(OtsClient.Result r) {
        rowsView.removeAllViews();
        if (!r.ok()) {
            statusView.setText(r.failure());
            return;
        }
        try {
            // A bare array, not a page - see the class note.
            OtsResponse.Page page = OtsResponse.page(r.body);
            statusView.setText(page.isEmpty()
                    ? "No members. Nobody can see this channel yet."
                    : page.results.size() + (page.results.size() == 1
                            ? " member" : " members"));
            boolean alt = false;
            for (JSONObject row : page.results) {
                rowsView.addView(memberRow(row, alt));
                alt = !alt;
            }
        } catch (Exception e) {
            Log.w(TAG, "could not read members of " + groupName, e);
            statusView.setText("The server sent something unreadable.");
        }
    }

    private View memberRow(JSONObject row, boolean alt) {
        final String username = row.optString("username", "");
        // The direction THIS membership has. Needed to remove it, and not
        // necessarily the same as any other group's.
        final String direction = row.optString("direction",
                OtsWriteGuard.DIRECTION_IN);
        boolean active = row.optBoolean("active", true);

        LinearLayout box = new LinearLayout(pluginContext);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setPadding(8, 12, 8, 12);
        if (alt)
            box.setBackgroundColor(0xFF1B2228);

        TextView name = new TextView(pluginContext);
        name.setText(username.isEmpty() ? "(unnamed)" : username);
        name.setTextSize(15);
        name.setTextColor(active ? 0xFFFFFFFF : 0xFF90A4AE);
        box.addView(name, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView dir = new TextView(pluginContext);
        dir.setText(direction + (active ? "" : "  (inactive)"));
        dir.setTextSize(12);
        dir.setTextColor(0xFFB0BEC5);
        dir.setPadding(0, 0, 16, 0);
        box.addView(dir);

        if (canWrite() && !username.isEmpty()) {
            Button remove = new Button(pluginContext);
            remove.setText("Remove");
            remove.setTextSize(10);
            remove.setAllCaps(false);
            remove.setPadding(14, 2, 14, 2);
            remove.setOnClickListener(
                    v -> confirmRemove(username, direction));
            box.addView(remove);
        }
        return box;
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    private boolean canWrite() {
        return session != null && session.isReady() && session.isAdmin()
                && !session.isLdap();
    }

    private void buildActionBar() {
        actionsBar.removeAllViews();
        if (!canWrite()) {
            actionsBar.setVisibility(View.GONE);
            return;
        }
        actionsBar.setVisibility(View.VISIBLE);
        actionsBar.addView(button("Add members", this::promptAddMembers));
        actionsBar.addView(button("Delete group", this::confirmDeleteGroup));
    }

    private Button button(String label, Runnable onClick) {
        Button b = new Button(pluginContext);
        b.setText(label);
        b.setTextSize(11);
        b.setAllCaps(false);
        if (onClick != null)
            b.setOnClickListener(v -> onClick.run());
        return b;
    }

    /** The group is already known, so it is not asked for. */
    private void promptAddMembers() {
        final EditText users = field("Usernames, comma separated");
        final TextView dirLabel = new TextView(pluginContext);
        dirLabel.setText("Direction");
        dirLabel.setTextSize(11);
        dirLabel.setTextColor(0xFF90A4AE);
        dirLabel.setPadding(0, 12, 0, 0);

        // Buttons rather than a text field: the server takes IN or OUT and
        // nothing else, so there is nothing to type.
        //
        // BOTH is offered even though the server has no such value, because
        // it is reachable - groups_users is keyed on (user, group,
        // DIRECTION), so a user can hold one IN row and one OUT row for the
        // same group. Picking it sends two requests. The note below says so,
        // because the consequence is visible: they then appear as two
        // members and need two removals.
        final String[] chosen = { OtsWriteGuard.DIRECTION_IN };
        final Button in = button(OtsWriteGuard.DIRECTION_IN, null);
        final Button out = button(OtsWriteGuard.DIRECTION_OUT, null);
        final Button both = button(DIRECTION_BOTH, null);
        final Button[] all = { in, out, both };
        View.OnClickListener pick = v -> {
            chosen[0] = v == in ? OtsWriteGuard.DIRECTION_IN
                    : v == out ? OtsWriteGuard.DIRECTION_OUT
                            : DIRECTION_BOTH;
            for (Button b : all)
                b.setAlpha(b == v ? 1f : 0.45f);
        };
        for (Button b : all)
            b.setOnClickListener(pick);
        out.setAlpha(0.45f);
        both.setAlpha(0.45f);
        LinearLayout dirRow = new LinearLayout(pluginContext);
        dirRow.setOrientation(LinearLayout.HORIZONTAL);
        dirRow.addView(in);
        dirRow.addView(out);
        dirRow.addView(both);

        final TextView dirNote = new TextView(pluginContext);
        dirNote.setText("Both adds two memberships, so the user appears"
                + " twice and needs removing twice. That is how the server"
                + " stores it.");
        dirNote.setTextSize(10);
        dirNote.setTextColor(0xFF78909C);
        dirNote.setPadding(0, 6, 0, 0);

        new AlertDialog.Builder(MapView.getMapView().getContext())
                .setTitle("Add to " + groupName)
                .setView(column(users, dirLabel, dirRow, dirNote))
                .setPositiveButton("Add", (d, w) -> {
                    List<String> list = splitList(
                            users.getText().toString());
                    boolean wantsBoth = DIRECTION_BOTH.equals(
                            chosen[0]);
                    // The guard checks what actually goes on the wire, so
                    // BOTH is validated as IN - which is one of the two
                    // requests it becomes.
                    String checkDir = wantsBoth
                            ? OtsWriteGuard.DIRECTION_IN : chosen[0];
                    String blocked = OtsWriteGuard.blockGroupMembers(list,
                            groupName, checkDir, session.isLdap());
                    if (blocked != null) {
                        say(blocked);
                        return;
                    }
                    String what = list.size()
                            + (list.size() == 1 ? " user" : " users")
                            + " to " + groupName;
                    if (wantsBoth)
                        writeAll(OtsRequest.addGroupMembersBoth(list,
                                groupName), "Added " + what
                                + ", in both directions");
                    else
                        write(OtsRequest.addGroupMembers(list, groupName,
                                chosen[0]), "Added " + what);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Uses the direction the server reported for that row. */
    private void confirmRemove(final String username,
            final String direction) {
        new AlertDialog.Builder(MapView.getMapView().getContext())
                .setTitle("Remove " + username + "?")
                .setMessage(username + " loses access to " + groupName
                        + ". The account itself is not touched.")
                .setPositiveButton("Remove", (d, w) -> write(
                        OtsRequest.removeGroupMember(username, groupName,
                                direction),
                        "Removed " + username + " from " + groupName))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmDeleteGroup() {
        String blocked = OtsWriteGuard.blockDeleteGroup(groupName);
        if (blocked != null) {
            say(blocked);
            return;
        }
        new AlertDialog.Builder(MapView.getMapView().getContext())
                .setTitle("Delete group " + groupName + "?")
                .setMessage("Members are not deleted, but they lose this"
                        + " channel.")
                .setPositiveButton("Delete", (d, w) -> {
                    write(OtsRequest.deleteGroup(groupName),
                            "Deleted " + groupName);
                    // The group this page is about no longer exists.
                    if (onBack != null)
                        ui.post(onBack);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * UI-only. The server has no such direction; see
     * {@link OtsRequest#addGroupMembersBoth}.
     */
    private static final String DIRECTION_BOTH = "BOTH";

    /**
     * Send several requests in order and report them as one outcome.
     *
     * Stops at the first failure and says which step it was. Carrying on
     * after one fails would leave the group half-changed while reporting
     * success, and "added in both directions" when only IN took is a worse
     * answer than a plain error.
     */
    private void writeAll(final List<OtsRequest> requests,
            final String successText) {
        statusView.setText("Working\u2026");
        run(() -> {
            String failure = null;
            int done = 0;
            for (OtsRequest request : requests) {
                OtsClient.Result r = session.client().send(request);
                String why = r.failure();
                if (why == null && !OtsResponse.succeeded(r.body))
                    why = "the server did not confirm it";
                if (why != null) {
                    failure = done == 0 ? why
                            : "step " + (done + 1) + " of "
                                    + requests.size() + " failed: " + why;
                    break;
                }
                done++;
            }
            final String problem = failure;
            ui.post(() -> {
                if (problem == null) {
                    say(successText);
                } else {
                    if (OtsWriteGuard.looksLikeLdapRefusal(problem))
                        session.noteLdapRefusal();
                    say(problem);
                }
                load();
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

    private EditText field(String hint) {
        EditText e = new EditText(pluginContext);
        e.setHint(hint);
        e.setTextSize(14);
        e.setSingleLine(true);
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
