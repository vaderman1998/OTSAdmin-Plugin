package com.atakmap.android.otsadmin;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.text.InputType;
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
 * <h3>Two panes, because one was not readable</h3>
 * This pane is small and stays small: whether the server is reachable, the
 * account in use, and a button per section. The rows themselves open on a
 * full-size page ({@link OtsListPane}).
 *
 * The first version put the list in here and there was no room for it. A row
 * is a name plus four or five fields — a user's roles, an EUD's team and
 * when it last reported — and at pane width that left two or three rows
 * visible with every one of them wrapped. Tabular data wants the screen; a
 * connection status does not.
 *
 * <h3>What it changes, and what it only shows</h3>
 * Writes are limited to <b>users and groups</b> — add, delete, and group
 * membership. Everything else is read-only on purpose: those are the
 * operations worth doing from a phone, and every extra write is another way
 * for a mistaken tap on a device in a pocket to cost something.
 *
 * <h3>Dialog resource ids go through the PLUGIN context</h3>
 * Every dialog passes a resolved String, never an {@code R.string} int. An
 * int id handed to a dialog built on ATAK's context resolves against
 * <b>ATAK's</b> resource table, so the number silently names some unrelated
 * ATAK resource — it compiles, and only shows up on device as a button
 * labelled after a drawable. Documented trap here, hit twice before.
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
    private TextView statusView, rolesView;
    private LinearLayout sectionsBar;
    private Button connectButton;

    private OtsSession session;
    private OtsListPane listPane;
    private OtsGroupPane groupPane;

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

    @Override
    public void onStart() {
        if (uiService == null)
            return;
        uiService.addToolbarItem(toolbarItem);
        SharedPreferences prefs = PreferenceManager
                .getDefaultSharedPreferences(
                        MapView.getMapView().getContext());
        session = new OtsSession(prefs);
        listPane = new OtsListPane(pluginContext, uiService, session,
                this::backFromList);
        groupPane = new OtsGroupPane(pluginContext, uiService, session,
                this::backToGroups);
        // A group row opens its own page; Back from there returns to the
        // group list rather than all the way to this pane.
        listPane.setGroupOpener((name, description) -> {
            listPane.hide();
            groupPane.show(name, description);
        });
    }

    @Override
    public void onStop() {
        if (uiService != null && toolbarItem != null)
            uiService.removeToolbarItem(toolbarItem);
        if (listPane != null)
            listPane.dispose();
        if (groupPane != null)
            groupPane.dispose();
        exec.shutdownNow();
        if (session != null)
            session.disconnect();
    }

    // ------------------------------------------------------------------
    // Main pane
    // ------------------------------------------------------------------

    private void showPane() {
        if (pane == null) {
            View root = PluginLayoutInflater.inflate(pluginContext,
                    R.layout.main_layout, null);
            statusView = root.findViewById(R.id.ots_status);
            rolesView = root.findViewById(R.id.ots_roles);
            sectionsBar = root.findViewById(R.id.ots_sections);
            connectButton = root.findViewById(R.id.ots_connect);
            connectButton.setOnClickListener(v -> connect());
            // Server actions hide behind a LONG PRESS on the status line.
            // Not security - anyone holding the phone can find it - but
            // these generate key material on the server, and that should
            // not share a row with Refresh where a thumb can find it by
            // accident.
            statusView.setOnLongClickListener(v -> {
                promptServerActions();
                return true;
            });
            root.findViewById(R.id.ots_settings)
                    .setOnClickListener(v -> promptServerSettings());
            buildSectionList();
            pane = new PaneBuilder(root)
                    .setMetaValue(Pane.RELATIVE_LOCATION,
                            Pane.Location.Default)
                    .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.4D)
                    .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.5D)
                    .build();
        }
        if (!uiService.isPaneVisible(pane))
            uiService.showPane(pane, null);
        renderSession();
        if (session != null && !session.isReady())
            connect();
    }

    /** Coming back from a section page: close it, show this one. */
    private void backFromList() {
        if (listPane != null)
            listPane.hide();
        showPane();
    }

    /**
     * Back from one group goes to the group LIST, not to the main pane.
     *
     * Three levels deep now, and skipping the middle one would mean
     * reopening Groups every time you finished with a group - which is
     * exactly when you are most likely to want the next one.
     */
    private void backToGroups() {
        if (groupPane != null)
            groupPane.hide();
        if (listPane != null)
            listPane.show(OtsSection.GROUPS);
    }

    /** One full-width button per section, straight from the enum. */
    private void buildSectionList() {
        sectionsBar.removeAllViews();
        for (final OtsSection s : OtsSection.values()) {
            Button b = new Button(pluginContext);
            // Marked, so it is clear before tapping which sections this
            // plugin can change and which it only reads.
            b.setText(s.writable ? s.label + "  (editable)" : s.label);
            b.setTextSize(13);
            b.setAllCaps(false);
            b.setOnClickListener(v -> openSection(s));
            sectionsBar.addView(b, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }
    }

    private void openSection(OtsSection s) {
        if (session == null || !session.isReady()) {
            say("Connect to the server first.");
            return;
        }
        if (listPane == null)
            return;
        // Close this one as the other opens, so a full-size page does not
        // sit on top of a small one that is still there behind it.
        if (pane != null && uiService.isPaneVisible(pane))
            uiService.closePane(pane);
        listPane.show(s);
    }

    private void renderSession() {
        if (session == null || statusView == null)
            return;
        statusView.setText(session.message());
        boolean ready = session.isReady();
        statusView.setTextColor(ready ? 0xFF81C784
                : session.state() == OtsSession.State.CONNECTING
                        ? 0xFFFFB74D : 0xFFFF5252);
        connectButton.setText(ready ? "Reconnect" : "Connect");
        if (ready) {
            rolesView.setVisibility(View.VISIBLE);
            List<String> roles = session.roles();
            String text = roles.isEmpty() ? "no roles reported"
                    : "roles: " + join(roles);
            rolesView.setText(session.isLdap()
                    ? text + " · LDAP owns accounts, so user and group"
                            + " changes are disabled"
                    : text);
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
            ui.post(this::renderSession);
        });
    }

    // ------------------------------------------------------------------
    // Server settings
    // ------------------------------------------------------------------

    /**
     * The server details, editable from the pane.
     *
     * The same three values live in ATAK Settings &gt; Tools &gt; OTS Admin
     * and both routes write the same preference keys, so neither holds a copy
     * of the other's state. This exists because the settings tree is five
     * taps from the map and this is one, and because saving here reconnects
     * straight away rather than leaving a second button to press.
     *
     * The password is masked but PRE-FILLED: a blank box would mean retyping
     * it to change only the URL, which is how people end up keeping
     * passwords in a text file instead.
     */
    private void promptServerSettings() {
        final SharedPreferences prefs = PreferenceManager
                .getDefaultSharedPreferences(
                        MapView.getMapView().getContext());
        final EditText url = field("https://tak.example.org", false);
        url.setText(prefs.getString(OtsSession.PREF_URL, ""));
        final EditText user = field("Username", false);
        user.setText(prefs.getString(OtsSession.PREF_USER, ""));
        final EditText pass = field("Password", true);
        pass.setText(prefs.getString(OtsSession.PREF_PASS, ""));

        TextView note = new TextView(pluginContext);
        note.setText("The same username and password you use on the web UI."
                + " Stored unencrypted in ATAK's preferences, so prefer a"
                + " dedicated administrator account.");
        note.setTextSize(11);
        note.setTextColor(0xFF90A4AE);
        note.setPadding(0, 10, 0, 0);

        new AlertDialog.Builder(MapView.getMapView().getContext())
                .setTitle("OpenTAKServer")
                .setView(column(url, user, pass, note))
                .setPositiveButton("Save and connect", (d, w) -> {
                    prefs.edit()
                            .putString(OtsSession.PREF_URL,
                                    url.getText().toString().trim())
                            .putString(OtsSession.PREF_USER,
                                    user.getText().toString().trim())
                            .putString(OtsSession.PREF_PASS,
                                    pass.getText().toString())
                            .apply();
                    // A changed server makes the old token meaningless.
                    session.disconnect();
                    renderSession();
                    connect();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * The operations that do something on the server rather than edit a
     * record.
     *
     * <h3>The list is short, and that is the API's doing</h3>
     * OpenTAKServer exposes <b>no shell, no service restart, no config
     * editing and no listener control</b>. The {@code /api/tcp/start} and
     * {@code /api/ssl/*} endpoints its documentation mentions are gone from
     * the current source \u2014 the EUD handlers are separate processes now.
     * Installing a server plugin is a multipart upload of a wheel, which is
     * not a phone operation.
     *
     * So what is left is certificate issuance, which is the one that
     * matters anyway: it generates key material and builds an enrolment
     * package, for somebody standing in front of you with a device that is
     * not on the net yet.
     *
     * <b>Arbitrary commands are not possible and should not be.</b> The only
     * route would be SSH from here, which would mean shell credentials on a
     * device whose preferences are not encrypted.
     */
    private void promptServerActions() {
        if (session == null || !session.isReady()) {
            say("Connect to the server first.");
            return;
        }
        new AlertDialog.Builder(MapView.getMapView().getContext())
                .setTitle("Server actions")
                .setItems(new String[] { "Issue a certificate" },
                        (d, which) -> promptIssueCertificate())
                .setNegativeButton("Close", null)
                .show();
    }

    private void promptIssueCertificate() {
        final EditText user = field("Username the certificate is for",
                false);
        final EditText uid = field("Device UID (optional)", false);
        TextView note = new TextView(pluginContext);
        note.setText("The server generates key material and builds an"
                + " enrolment data package. Leave the UID blank for a"
                + " certificate that is not tied to one device.");
        note.setTextSize(10);
        note.setTextColor(0xFF78909C);
        note.setPadding(0, 10, 0, 0);
        new AlertDialog.Builder(MapView.getMapView().getContext())
                .setTitle("Issue a certificate")
                .setView(column(user, uid, note))
                .setPositiveButton("Issue", (d, w) -> {
                    final String username =
                            user.getText().toString().trim();
                    if (username.isEmpty()) {
                        say("A username is needed.");
                        return;
                    }
                    confirmByTyping("Issue for " + username + "?",
                            "This generates key material on the server."
                                    + " Type the server host to confirm:",
                            session.client().hostLabel(),
                            () -> sendServerAction(
                                    OtsRequest.issueCertificate(username,
                                            uid.getText().toString()),
                                    "Issued a certificate for "
                                            + username));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * A confirmation that cannot be dismissed by reflex.
     *
     * Typing the host is deliberate friction, and it also makes you read
     * WHICH server you are about to change - the plugin can be pointed at a
     * different one in two taps, and "I was on the other server" is the
     * mistake this prevents.
     */
    private void confirmByTyping(String title, String message,
            final String expected, final Runnable onConfirm) {
        final EditText typed = field(expected, false);
        TextView msg = new TextView(pluginContext);
        msg.setText(message);
        msg.setTextSize(12);
        msg.setTextColor(0xFFB0BEC5);
        msg.setPadding(0, 0, 0, 8);
        new AlertDialog.Builder(MapView.getMapView().getContext())
                .setTitle(title)
                .setView(column(msg, typed))
                .setPositiveButton("Confirm", (d, w) -> {
                    if (!expected.equalsIgnoreCase(
                            typed.getText().toString().trim())) {
                        say("That is not " + expected + ". Nothing was"
                                + " sent.");
                        return;
                    }
                    onConfirm.run();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Send one server action and report the server's own answer. */
    private void sendServerAction(final OtsRequest request,
            final String successText) {
        run(() -> {
            final OtsClient.Result r = session.client().send(request);
            ui.post(() -> {
                String failure = r.failure();
                if (failure == null && OtsResponse.succeeded(r.body))
                    say(successText);
                else
                    say(failure != null ? failure
                            : "the server did not confirm it");
            });
        });
    }

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
                // Nothing registered yet, the normal first run.
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

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0)
                sb.append(", ");
            sb.append(p);
        }
        return sb.toString();
    }

    private void say(String text) {
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
