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
        REVIEW;

        /** The verdict's part of a message key: {@code alert.verdict.<key>}, {@code discord.title-<key>}. */
        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
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
            } else {
                String command = commandName(action);
                if (!command.isEmpty()) {
                    out.append(':').append(command);
                }
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
            String detail = piece.length > 1 ? piece[1] : "";
            actions.add(type.get() == ModerationActionType.MUTE
                    ? new ModerationAction(type.get(), "", "", parseInt(detail), "")
                    : new ModerationAction(type.get(), detail, "", 0, ""));
        }
        return Optional.of(new Outcome(verdict, actions, strike));
    }

    /** The name of the command an action ran, safe to store: "tempmute" for "/essentials:tempmute %PLAYER% 1h". */
    private static String commandName(ModerationAction action) {
        String command = ChatModerationActionExecutor.commandFor(action).trim();
        int space = command.indexOf(' ');
        String name = space < 0 ? command : command.substring(0, space);
        name = name.substring(name.lastIndexOf(':') + 1);
        return name.replaceAll("[^A-Za-z0-9_.-]", "");
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
