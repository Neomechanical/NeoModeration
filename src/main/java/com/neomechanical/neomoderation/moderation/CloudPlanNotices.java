package com.neomechanical.neomoderation.moderation;

import java.util.List;
import java.util.Map;

/**
 * What a refused cloud check means and what the admin is told about this server's cloud plan, chosen from what the
 * trial service said ({@link CloudPlanWatch}). {@code /nmod status}, {@code /nmod test}, {@code /nmod doctor}, the
 * console and an admin's join line all name a refusal through {@link #refusal}, each in its own words: before 1.7.1
 * each chose for itself, and only one knew an ended trial from a bad key. Dates stay ISO here; whoever shows a notice
 * writes them in the reader's words.
 */
public final class CloudPlanNotices {

    /** A trial with this many days left or fewer is ending: an admin hears about it once. */
    public static final int ENDING_SOON_DAYS = 2;

    /** Every {@link Reason#name()}: each surface has a message for each. */
    public static final List<String> REASONS = List.of("auth", "credits", "trial-ended", "trial-revoked",
            "trial-credits");

    /** Every {@link #lastCheck}: {@code /nmod status} and {@code /nmod doctor} have a message for each. */
    public static final List<String> LAST_CHECKS = List.of("auth", "credits", "refused");

    private CloudPlanNotices() {
    }

    /**
     * Why a cloud check was refused: {@code name} ends the message key on every surface ({@code auth},
     * {@code credits}, {@code trial-ended}, {@code trial-revoked}, {@code trial-credits}), with the link that fixes
     * it and the date it names (each empty when none).
     */
    public record Reason(String name, String url, String dateIso) {
    }

    public record Notice(String key, String url, String dateIso) {
    }

    /**
     * What a 401 or 402 means for this server, or null for any other result. An ended trial is not a bad key, and a
     * running trial that ran out of credits is kept in a free account before credits can be added to it: buying
     * them elsewhere funds another workspace. That takes the trial's signed claim link, which whoever opens it first
     * can use, so it is only ever a button an admin clicks in game ({@link #join}, {@code /nmod trial status}): a
     * {@code trial-credits} reason carries no link, and its texts send the reader to {@code /nmod trial status}.
     */
    public static Reason refusal(CloudPlanWatch.Snapshot snapshot, ModerationApiResult.Kind kind) {
        if (kind != ModerationApiResult.Kind.CLIENT_AUTH && kind != ModerationApiResult.Kind.INSUFFICIENT_CREDITS) {
            return null;
        }
        TrialClient.TrialStatusResult status = snapshot.status();
        return switch (snapshot.state()) {
            case TRIAL_ENDED -> ended(status);
            case TRIAL_ACTIVE -> kind == ModerationApiResult.Kind.INSUFFICIENT_CREDITS
                    ? new Reason("trial-credits", "", "")
                    : new Reason("auth", CloudRecovery.API_KEYS_URL, "");
            default -> kind == ModerationApiResult.Kind.CLIENT_AUTH
                    ? new Reason("auth", CloudRecovery.API_KEYS_URL, "")
                    : new Reason("credits", CloudRecovery.BILLING_URL, "");
        };
    }

    /**
     * A running trial whose credits are used up: the trial service's watch heard a 402, or the last cloud check was
     * one (the watch hears only the first refusal of each kind in a run of them). Status, the doctor and {@code /nmod
     * trial status} all read it here.
     */
    public static boolean creditsUsedUp(CloudPlanWatch.Snapshot plan, ModerationApiResult.Kind lastCheck) {
        return plan.state() == CloudPlanWatch.State.TRIAL_ACTIVE
                && (plan.refusal() == ModerationApiResult.Kind.INSUFFICIENT_CREDITS
                || lastCheck == ModerationApiResult.Kind.INSUFFICIENT_CREDITS);
    }

    /**
     * The cloud plan's line, the same on every surface that shows one: its {@code name} ends the message key
     * ({@code trial}, {@code trial-credits}, {@code trial-ended}, {@code trial-revoked}, {@code standard}), and its
     * values carry a date as ISO, which each surface writes in the reader's words.
     */
    public record PlanLine(String name, Map<String, String> values) {
    }

    /** The plan line for what the trial service said and the last cloud check, or null when nothing was said. */
    public static PlanLine planLine(CloudPlanWatch.Snapshot plan, ModerationApiResult.Kind lastCheck, long nowMillis) {
        TrialClient.TrialStatusResult status = plan.status();
        return switch (plan.state()) {
            case TRIAL_ACTIVE -> new PlanLine(creditsUsedUp(plan, lastCheck) ? "trial-credits" : "trial", Map.of(
                    "days", String.valueOf(status.daysLeft(nowMillis)), "date", status.expiresAt()));
            case TRIAL_ENDED -> {
                Reason reason = ended(status);
                yield new PlanLine(reason.name(), Map.of("date", reason.dateIso(), "url", reason.url()));
            }
            case STANDARD -> new PlanLine("standard", Map.of());
            case KEY_REJECTED, UNKNOWN -> null;
        };
    }

    /**
     * Whether a server out of credits gets more by keeping its trial in a free account first, never through billing,
     * which funds another workspace: a running trial. {@code /nmod usage} and the doctor read it here.
     */
    public static boolean keepsTrialFirst(CloudPlanWatch.Snapshot plan) {
        return plan.state() == CloudPlanWatch.State.TRIAL_ACTIVE;
    }

    /**
     * What a last-check line says of a refusal: its reason, or only that it was refused when the cloud plan's line
     * beside it already says why and what to do, as it does for every trial reason. Each fact is said once.
     */
    public static String lastCheck(Reason reason) {
        return reason.name().startsWith("trial-") ? "refused" : reason.name();
    }

    /**
     * Why the cloud cannot work for this key before any check was refused, or null when nothing says it cannot:
     * the trial ended or the trial service does not know the key.
     */
    public static Reason knownProblem(CloudPlanWatch.Snapshot snapshot) {
        return switch (snapshot.state()) {
            case TRIAL_ENDED -> ended(snapshot.status());
            case KEY_REJECTED -> new Reason("auth", CloudRecovery.API_KEYS_URL, "");
            default -> null;
        };
    }

    /**
     * The console line for an answer: the refusal it answers, else the refusal still standing (an answer that comes
     * later may change what it means: a 402 told while nothing was known turns out to be a trial's), else a problem
     * the answer shows; null when none.
     */
    public static Notice console(CloudPlanWatch.Snapshot snapshot, ModerationApiResult.Kind refusal) {
        ModerationApiResult.Kind standing = refusal != null ? refusal : snapshot.refusal();
        Reason reason = standing == null ? knownProblem(snapshot) : refusal(snapshot, standing);
        return reason == null ? null : new Notice("cloud-plan.console-" + reason.name(), reason.url(), reason.dateIso());
    }

    /**
     * The line an admin gets once on joining, or null. A trial the clock ended but the service has not yet called
     * ended says nothing yet: it may have been kept in an account in its last hour, and the notice is once per install.
     */
    public static Notice join(CloudPlanWatch.Snapshot snapshot, long nowMillis) {
        TrialClient.TrialStatusResult status = snapshot.status();
        if (snapshot.state() == CloudPlanWatch.State.TRIAL_ENDED && !status.hasEnded()) {
            return null;
        }
        return switch (snapshot.state()) {
            case TRIAL_ACTIVE -> snapshot.refusal() == ModerationApiResult.Kind.INSUFFICIENT_CREDITS
                    ? new Notice("cloud-plan.join-trial-credits", link(status.upgradeUrl(), CloudRecovery.SIGNUP_URL),
                            "")
                    : status.daysLeft(nowMillis) <= ENDING_SOON_DAYS
                    ? new Notice("cloud-plan.join-trial-ending", link(status.upgradeUrl(), CloudRecovery.SIGNUP_URL),
                            status.expiresAt())
                    : null;
            case TRIAL_ENDED -> {
                Reason reason = ended(status);
                yield new Notice("cloud-plan.join-" + reason.name(), reason.url(), reason.dateIso());
            }
            default -> null;
        };
    }

    /** The marker file in the plugin's folder that keeps a join notice to once per install: it has one trial. */
    public static String joinMarker(Notice notice) {
        return "." + notice.key().substring(notice.key().lastIndexOf('.') + 1);
    }

    /** The join notice's button: an ended or stopped trial cannot be kept, so its way on is a new account. */
    public static String joinButton(Notice notice) {
        return notice.key().endsWith("-ended") || notice.key().endsWith("-revoked")
                ? "trial.create-account-button" : "trial.keep-button";
    }

    /**
     * An ended trial cannot be kept, so its way on is a new account: never the claim link a running trial sent,
     * which the platform refuses after the end. A revoked trial names no date: the service sends the planned end,
     * not the day it stopped.
     */
    private static Reason ended(TrialClient.TrialStatusResult status) {
        return status.isRevoked() ? new Reason("trial-revoked", CloudRecovery.SIGNUP_URL, "")
                : new Reason("trial-ended", CloudRecovery.SIGNUP_URL, status.expiresAt());
    }

    private static String link(String sent, String fallback) {
        return sent == null || sent.isBlank() ? fallback : sent;
    }
}
