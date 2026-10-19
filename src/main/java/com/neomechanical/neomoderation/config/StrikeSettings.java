package com.neomechanical.neomoderation.config;



import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Progressive-enforcement configuration. Strikes accumulate per player while
 * they keep triggering detections; after {@code decayMinutes} without a strike
 * the counter resets. When the count reaches an escalation rung, that action
 * runs in addition to the regular detection actions.
 */
public record StrikeSettings(boolean enabled, int decayMinutes, List<Escalation> escalation) {
    /** The bundled ladder's reason; players read it in their own language. */
    public static final String DEFAULT_REASON = "Repeated inappropriate chat";

    public record Escalation(int atStrikes, ModerationAction action) {
    }

    public static StrikeSettings from(ConfigView config) {
        List<Escalation> ladder = new ArrayList<>();
        for (Map<?, ?> raw : config.getMapList("moderation.strikes.escalation")) {
            int atStrikes = parseInt(raw.get("atStrikes"));
            if (atStrikes <= 0) {
                continue;
            }
            ModerationAction.tryFrom(raw).ifPresent(action -> ladder.add(new Escalation(atStrikes, action)));
        }
        if (ladder.isEmpty()) {
            ladder.add(new Escalation(4, new ModerationAction(
                    ModerationActionType.KICK, "", "", 300, DEFAULT_REASON)));
        }
        ladder.sort(java.util.Comparator.comparingInt(Escalation::atStrikes));
        return new StrikeSettings(
                config.getBoolean("moderation.strikes.enabled", false),
                Math.max(1, config.getInt("moderation.strikes.decayMinutes", 30)),
                List.copyOf(ladder)
        );
    }

    private static int parseInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
