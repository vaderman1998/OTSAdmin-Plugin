package com.atakmap.android.otsadmin;

/**
 * One thing the plugin can show a list of.
 *
 * <h3>Why this is a descriptor and not ten panes</h3>
 * Ten endpoints that all return {@code {"results":[…]}} do not need ten
 * screens' worth of code. Each section says what to ask for and which fields
 * are worth reading on a phone, and one pane renders any of them. The useful
 * consequence is that adding the next read-only view is a line here, not a
 * new file — and the fields are all in one place where they can be checked
 * against the server's {@code to_json()} methods.
 *
 * <h3>Which fields</h3>
 * The field lists are deliberately short. A server row has fifteen or twenty
 * keys and almost none of them earn space on a phone; these are the ones that
 * answer the question you opened the section to ask. {@code EUDS} is the
 * example: callsign, who it belongs to, and when it last said anything — not
 * its platform string or its data-package hash.
 *
 * Plain Java, no Android imports, so the field choices can be asserted.
 */
public enum OtsSection {

    /** Writable: add and delete. */
    USERS("Users", true) {
        @Override
        public OtsRequest request(int page) {
            return OtsRequest.users(page, PER_PAGE);
        }

        @Override
        public String[] fields() {
            return new String[] { "username", "roles", "active",
                    "last_login_at" };
        }

        @Override
        public String titleField() {
            return "username";
        }
    },

    /** Writable: add, delete, and membership. */
    GROUPS("Groups", true) {
        @Override
        public OtsRequest request(int page) {
            return OtsRequest.groups(page, PER_PAGE);
        }

        @Override
        public String[] fields() {
            return new String[] { "group_name", "name", "description",
                    "direction", "active" };
        }

        @Override
        public String titleField() {
            // The server has used both spellings on this resource, so the
            // pane tries one and falls back to the other.
            return "name";
        }
    },

    EUDS("Devices (EUDs)", false) {
        @Override
        public OtsRequest request(int page) {
            return OtsRequest.euds(page, PER_PAGE);
        }

        @Override
        public String[] fields() {
            return new String[] { "callsign", "username", "team",
                    "last_event_time", "last_status", "platform",
                    "version" };
        }

        @Override
        public String titleField() {
            return "callsign";
        }
    },

    CERTIFICATES("Certificates", false) {
        @Override
        public OtsRequest request(int page) {
            return OtsRequest.certificates(page, PER_PAGE);
        }

        @Override
        public String[] fields() {
            return new String[] { "callsign", "username", "expiration_date",
                    "cert_password" };
        }

        @Override
        public String titleField() {
            return "callsign";
        }
    },

    DATA_PACKAGES("Data packages", true) {
        @Override
        public OtsRequest request(int page) {
            return OtsRequest.dataPackages(page, PER_PAGE);
        }

        @Override
        public String[] fields() {
            return new String[] { "filename", "submission_user", "size",
                    "submission_time", "hash" };
        }

        @Override
        public String titleField() {
            return "filename";
        }
    },

    VIDEO("Video streams", false) {
        @Override
        public OtsRequest request(int page) {
            return OtsRequest.videoStreams(page, PER_PAGE);
        }

        @Override
        public String[] fields() {
            return new String[] { "path", "username", "ready", "protocol",
                    "rtsp_link" };
        }

        @Override
        public String titleField() {
            return "path";
        }
    },

    PROFILES("Device profiles", true) {
        @Override
        public OtsRequest request(int page) {
            return OtsRequest.deviceProfiles(page, PER_PAGE);
        }

        @Override
        public String[] fields() {
            // eud_uid and preference_key are what a delete is keyed
            // on, so they have to be visible to be actionable.
            return new String[] { "preference_key", "eud_uid", "value",
                    "active", "enrollment", "connection", "tool" };
        }

        @Override
        public String titleField() {
            return "preference_key";
        }
    },

    MISSIONS("Missions", false) {
        @Override
        public OtsRequest request(int page) {
            return OtsRequest.missions(page, PER_PAGE);
        }

        @Override
        public String[] fields() {
            return new String[] { "name", "creator_uid", "create_time",
                    "description" };
        }

        @Override
        public String titleField() {
            return "name";
        }
    },

    /** Not paginated; returns whatever the scheduler is running. */
    SCHEDULER("Scheduled jobs", true) {
        @Override
        public OtsRequest request(int page) {
            return OtsRequest.schedulerJobs();
        }

        @Override
        public String[] fields() {
            return new String[] { "id", "name", "next_run_time", "trigger" };
        }

        @Override
        public String titleField() {
            return "id";
        }
    },

    /** Not paginated. Administrator only server-side. */
    PLUGINS("Server plugins", true) {
        @Override
        public OtsRequest request(int page) {
            return OtsRequest.plugins();
        }

        @Override
        public String[] fields() {
            return new String[] { "name", "distro", "version", "enabled" };
        }

        @Override
        public String titleField() {
            return "name";
        }
    };

    /**
     * Rows per request.
     *
     * The server's own default is 10, which means four taps to see a team of
     * forty. 50 is one screen of scrolling and one request.
     */
    public static final int PER_PAGE = 50;

    public final String label;
    /** Whether this plugin can change anything here. */
    public final boolean writable;

    OtsSection(String label, boolean writable) {
        this.label = label;
        this.writable = writable;
    }

    /** The call for one page. */
    public abstract OtsRequest request(int page);

    /** Fields worth showing, in the order they should appear. */
    public abstract String[] fields();

    /** The field to use as a row's heading. */
    public abstract String titleField();

    /**
     * Whether this section needs the administrator role at all.
     *
     * Groups, profiles and the server's plugin list are administrator-only
     * server-side, so a non-admin account gets a 403 rather than an empty
     * list. Saying which up front beats showing a bare failure.
     */
    public boolean needsAdmin() {
        return this == GROUPS || this == PROFILES || this == PLUGINS;
    }
}
