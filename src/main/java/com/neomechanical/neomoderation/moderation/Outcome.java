package com.neomechanical.neomoderation.moderation;

import com.neomechanical.neomoderation.config.ModerationAction;
import com.neomechanical.neomoderation.config.ModerationActionType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * What one detection did: blocked, censored or only flagged for review, the
 * actions that ran after it, and the strike it added.
 *
 * <p>A case stores it as text such as {@code blocked|mute:300|kick|strike:4},
 * so the case reads in whichever language its reader uses. Cases saved before
 * 1.7.0 hold English text instead; {@link #decode} returns empty for those.</p>
 */
public record Outcome(Verdict verdict, List<ModerationAction> actions, int strike) {

    public enum Verdict {
        BLOCKED,
        CENSORED,
        REVIEW
    }

    public Outcome {
        actions = List.copyOf(actions);
    }

    public String encode() {
        StringBuilder out = new StringBuilder(verdict.name().toLowerCase(Locale.ROOT));
        for (ModerationAction action : actions) {
            out.append('|').append(action.type().name().toLowerCase(Locale.ROOT));
            if (action.type() == ModerationActionType.MUTE) {
                out.append(':').append(action.durationSeconds());
            }
        }
        if (strike > 0) {
            out.append("|strike:").append(strike);
        }
        return out.toString();
    }

    public static Optional<Outcome> decode(String stored) {
        if (stored == null || stored.isBlank()) {
            return Optional.empty();
        }
        String[] parts = stored.split("\\|");
        Verdict verdict;
        try {
            verdict = Verdict.valueOf(parts[0].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException notEncoded) {
            return Optional.empty();
        }
        List<ModerationAction> actions = new ArrayList<>();
        int strike = 0;
        for (int i = 1; i < parts.length; i++) {
            String[] piece = parts[i].split(":", 2);
            if ("strike".equals(piece[0])) {
                strike = parseInt(piece.length > 1 ? piece[1] : "0");
                continue;
            }
            Optional<ModerationActionType> type = ModerationActionType.parse(piece[0]);
            if (type.isEmpty()) {
                return Optional.empty();
            }
            int seconds = piece.length > 1 ? parseInt(piece[1]) : 0;
            actions.add(new ModerationAction(type.get(), "", "", seconds, ""));
        }
        return Optional.of(new Outcome(verdict, actions, strike));
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
