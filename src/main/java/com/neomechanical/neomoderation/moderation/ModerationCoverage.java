package com.neomechanical.neomoderation.moderation;

import com.neomechanical.neomoderation.config.ModerationMode;
import com.neomechanical.neomoderation.config.ModerationSettings;
import com.neomechanical.neomoderation.config.SurfaceSettings.SurfaceMode;

/** Configured checks that can receive content; cloud health is a separate question. */
public record ModerationCoverage(
        boolean offlineRulesConfigured,
        boolean localRules,
        boolean chatSpam,
        boolean commandSpam,
        boolean cloudText,
        boolean mapArt,
        boolean localEnforces
) {
    public boolean hasLocalChecks() {
        return localRules || spam();
    }

    public boolean spam() {
        return chatSpam || commandSpam;
    }

    public boolean hasCloudChecks() {
        return cloudText || mapArt;
    }

    public static ModerationCoverage from(ModerationSettings settings) {
        if (!settings.enabled()) {
            return new ModerationCoverage(false, false, false, false, false, false, false);
        }
        boolean hasRules = settings.offline().enabled()
                && (settings.offline().blockAnyUrl()
                        || settings.offline().blockServerAds()
                        || !settings.offline().effectiveWords().isEmpty()
                        || !settings.offline().bannedUrls().isEmpty());
        boolean chatRules = hasRules && settings.scanAsyncChat();
        boolean surfaceRules = hasRules && settings.surfaces().enabledCount() > 0;
        boolean chatSpam = settings.spam().enabled() && settings.scanAsyncChat()
                && (settings.spam().messagesPer10s() > 0
                        || settings.spam().duplicateLimit() > 0
                        || settings.spam().capsPercent() > 0
                        || settings.spam().maxCharRun() > 0);
        boolean commandSpam = settings.spam().enabled()
                && settings.surfaces().command() != SurfaceMode.OFF
                && settings.spam().commandsPer10s() > 0;
        boolean localEnforces = settings.mode() == ModerationMode.ENFORCE
                && (chatRules || chatSpam
                        || (surfaceRules && (enforces(settings.surfaces().sign())
                                || enforces(settings.surfaces().book())
                                || enforces(settings.surfaces().anvil())
                                || enforces(settings.surfaces().command())))
                        || (commandSpam && enforces(settings.surfaces().command())));
        boolean hasKey = !settings.api().apiKey().isBlank();
        return new ModerationCoverage(hasRules, chatRules || surfaceRules, chatSpam, commandSpam,
                hasKey && settings.scanAsyncChat() && settings.categories().enabledCount() > 0,
                hasKey && settings.mapArt().enabled()
                        && (settings.mapArt().scanOnHold() || settings.mapArt().scanOnFrameInteract()),
                localEnforces);
    }

    private static boolean enforces(SurfaceMode mode) {
        return mode == SurfaceMode.BLOCK || mode == SurfaceMode.CENSOR;
    }
}
