package com.neomechanical.neomoderation.messages;

import com.neomechanical.neomoderation.commands.DurationParser;
import com.neomechanical.neomoderation.config.ModerationAction;
import com.neomechanical.neomoderation.moderation.ChatModerationActionExecutor;
import com.neomechanical.neomoderation.moderation.Outcome;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Actions and outcomes in words, in the reader's language: "mute for 10m, kick". */
public final class ActionText {

    private ActionText() {
    }

    /** The actions that follow a block, or "nothing else" when there are none. */
    public static String describe(MessageService messages, CommandSender reader, List<ModerationAction> actions) {
        if (actions.isEmpty()) {
            return messages.text(reader, "action.name.nothing");
        }
        List<String> names = new ArrayList<>(actions.size());
        for (ModerationAction action : actions) {
            names.add(name(messages, reader, action));
        }
        return String.join(", ", names);
    }

    public static String name(MessageService messages, CommandSender reader, ModerationAction action) {
        return switch (action.type()) {
            case CLEAR_CHAT -> messages.text(reader, "action.name.clear");
            case MUTE -> messages.format(reader, "action.name.mute",
                    Map.of("duration", DurationParser.format(action.durationSeconds())));
            case KICK -> messages.text(reader, "action.name.kick");
            case BAN -> messages.text(reader, "action.name.ban");
            default -> messages.format(reader, "action.name.command", Map.of("command", commandName(action)));
        };
    }

    /** The command an action runs, by name only: "tempmute" for "/tempmute %PLAYER% 300s". */
    private static String commandName(ModerationAction action) {
        String command = ChatModerationActionExecutor.commandFor(action).trim();
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        int space = command.indexOf(' ');
        return space < 0 ? command : command.substring(0, space);
    }

    /** What ran after a detection, with its strike: "mute for 10m (strike 2)"; empty when nothing ran. */
    public static String afterwards(MessageService messages, CommandSender reader, Outcome outcome) {
        String actions = outcome.actions().isEmpty() ? "" : describe(messages, reader, outcome.actions());
        if (outcome.strike() <= 0) {
            return actions;
        }
        String strike = messages.format(reader, "action.strike", Map.of("count", String.valueOf(outcome.strike())));
        return actions.isEmpty() ? strike : actions + " (" + strike + ")";
    }
}
