package com.neomechanical.neomoderation;

/** The plugin's permissions, as plugin.yml declares them. */
public final class Permissions {
    /** Every command, settings and the cloud key included. Grants {@link #MODERATE}. */
    public static final String ADMIN = "neomoderation.admin";
    /** Day-to-day moderation: help, status, test, privacy, cases, mute and unmute. */
    public static final String MODERATE = "neomoderation.moderate";
    /** In-game alerts when something is caught. */
    public static final String NOTIFY = "neomoderation.notify";
    /** Nothing this player writes or holds is checked. */
    public static final String BYPASS = "neomoderation.bypass";

    private Permissions() {
    }
}
