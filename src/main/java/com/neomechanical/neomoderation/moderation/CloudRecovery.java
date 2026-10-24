package com.neomechanical.neomoderation.moderation;

/**
 * Public recovery destinations shown to server operators after cloud failures.
 *
 * Keep these centralized so command output and console diagnostics send admins to
 * the same place without ever echoing an API key.
 */
public final class CloudRecovery {
    public static final String SIGNUP_URL = "https://neomechanical.com/signup?src=neomoderation";
    public static final String SIGN_IN_URL = "https://neomechanical.com/login?src=neomoderation";
    public static final String API_KEYS_URL = "https://neomechanical.com/api-keys?src=neomoderation";
    public static final String BILLING_URL = "https://neomechanical.com/billing?src=neomoderation_credits";
    /** The terms starting a cloud plan accepts, and the policy that says how its data is handled. */
    public static final String TERMS_URL = "https://neomechanical.com/terms";
    public static final String PRIVACY_URL = "https://neomechanical.com/privacy";
    /** Where a bug in the plugin is reported: its public repository's issues. */
    public static final String ISSUES_URL = "https://github.com/Neomechanical/NeoModeration/issues";

    private CloudRecovery() {
    }
}
