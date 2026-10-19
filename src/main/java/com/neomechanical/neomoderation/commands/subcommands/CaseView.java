package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.commands.DurationParser;
import com.neomechanical.neomoderation.messages.ActionText;
import com.neomechanical.neomoderation.messages.MenuRenderer;
import com.neomechanical.neomoderation.messages.MenuRenderer.Button;
import com.neomechanical.neomoderation.messages.MessageService;
import com.neomechanical.neomoderation.messages.Reasons;
import com.neomechanical.neomoderation.moderation.CaseLog.CaseRecord;
import com.neomechanical.neomoderation.moderation.Outcome;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Renders cases for /nmod cases, in the reader's language, with click-to-act buttons in game. */
final class CaseView {
    private CaseView() {
    }

    static MenuRenderer.Line row(MessageService messages, CommandSender reader, CaseRecord row, String label) {
        Map<String, String> values = common(messages, reader, row);
        values.put("status", messages.text(reader, "cases.status." + statusKey(row)));
        String command = "/" + label + " case " + row.id();
        return MenuRenderer.clickableLine(messages.format(reader, "cases.line", values), command,
                messages.format(reader, "cases.button.open-hover", Map.of("id", String.valueOf(row.id()))),
                MenuRenderer.ClickAction.RUN);
    }

    static List<MenuRenderer.Line> detail(MessageService messages, CommandSender reader, CaseRecord row, String label) {
        Map<String, String> values = common(messages, reader, row);
        List<MenuRenderer.Line> lines = new ArrayList<>();
        lines.add(MenuRenderer.text(messages.format(reader, "cases.detail", values)));
        lines.add(MenuRenderer.text(messages.format(reader, "cases.detail-reason", values)));
        String afterwards = afterwards(messages, reader, row);
        if (!afterwards.isEmpty()) {
            lines.add(MenuRenderer.text(messages.format(reader, "cases.detail-actions", Map.of("actions", afterwards))));
        }
        lines.add(MenuRenderer.text(messages.format(reader, "cases.detail-status",
                Map.of("status", messages.text(reader, "cases.status-name." + statusKey(row))))));
        if (row.preview() != null && !row.preview().isBlank()) {
            lines.add(MenuRenderer.text(messages.format(reader, "cases.preview", Map.of("value", row.preview()))));
        }
        lines.add(MenuRenderer.buttons(buttons(messages, reader, row, label)));
        return lines;
    }

    private static List<Button> buttons(MessageService messages, CommandSender reader, CaseRecord row, String label) {
        String id = String.valueOf(row.id());
        Map<String, String> ids = Map.of("id", id, "player", row.player());
        List<Button> buttons = new ArrayList<>();
        buttons.add(button(messages, reader, "resolve", ids, "/" + label + " cases resolve " + id));
        buttons.add(button(messages, reader, "dismiss", ids, "/" + label + " cases dismiss " + id));
        Reasons.Parsed reason = Reasons.parse(row.reason());
        if (reason.kind() == Reasons.Kind.WORD && !reason.detail().isBlank()) {
            buttons.add(button(messages, reader, "allow", Map.of("word", reason.detail()),
                    "/" + label + " allow word add " + reason.detail()));
        }
        buttons.add(button(messages, reader, "history", ids, "/" + label + " cases " + row.player()));
        buttons.add(button(messages, reader, "mute", ids, "/" + label + " mute " + row.player() + " 10m"));
        return buttons;
    }

    private static Button button(MessageService messages, CommandSender reader, String name,
                                 Map<String, String> values, String command) {
        return new Button(messages.format(reader, "cases.button." + name, values), command,
                messages.format(reader, "cases.button." + name + "-hover", values), MenuRenderer.ClickAction.RUN);
    }

    private static Map<String, String> common(MessageService messages, CommandSender reader, CaseRecord row) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("id", String.valueOf(row.id()));
        values.put("verdict", messages.text(reader, verdictKey(row)));
        values.put("player", row.player());
        values.put("surface", messages.text(reader, "surface-name." + row.surface()));
        values.put("reason", Reasons.describe(messages, reader, row.reason()));
        values.put("ago", DurationParser.approximate(Math.max(0, (System.currentTimeMillis() - row.timestamp()) / 1000L)));
        return values;
    }

    private static String verdictKey(CaseRecord row) {
        Outcome.Verdict verdict = Outcome.decode(row.action()).map(Outcome::verdict)
                .orElse("monitor".equals(row.mode()) ? Outcome.Verdict.REVIEW : Outcome.Verdict.BLOCKED);
        return switch (verdict) {
            case BLOCKED -> "alert.verdict.blocked";
            case CENSORED -> "alert.verdict.censored";
            case REVIEW -> "alert.verdict.review";
        };
    }

    /** What ran after the detection, in words; a case saved before 1.7.0 keeps its English text. */
    private static String afterwards(MessageService messages, CommandSender reader, CaseRecord row) {
        return Outcome.decode(row.action())
                .map(outcome -> ActionText.afterwards(messages, reader, outcome))
                .orElse(row.action() == null ? "" : row.action());
    }

    private static String statusKey(CaseRecord row) {
        return switch (row.status()) {
            case "resolved", "dismissed" -> row.status();
            default -> "open";
        };
    }
}
