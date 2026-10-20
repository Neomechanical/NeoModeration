package com.neomechanical.neomoderation.messages;

import org.bukkit.command.CommandSender;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/** Dates as people write them in the reader's language: "2 November 2026", "2. November 2026". */
public final class DateText {

    private DateText() {
    }

    /** {@code iso} (an instant or a date) in words; the text unchanged when it is neither. */
    public static String of(MessageService messages, CommandSender reader, String iso) {
        LocalDate date = parse(iso);
        if (date == null) {
            return iso == null ? "" : iso;
        }
        String pattern = MessageService.plain(messages.text(reader, "format.date"));
        Locale locale = Locale.forLanguageTag(messages.localeCodeOf(reader).replace('_', '-'));
        try {
            return DateTimeFormatter.ofPattern(pattern, locale).format(date);
        } catch (IllegalArgumentException badPattern) {
            return date.toString();
        }
    }

    static LocalDate parse(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(iso.trim()).atZone(ZoneOffset.UTC).toLocalDate();
        } catch (DateTimeParseException notAnInstant) {
            try {
                return LocalDate.parse(iso.trim().length() >= 10 ? iso.trim().substring(0, 10) : iso.trim());
            } catch (DateTimeParseException notADate) {
                return null;
            }
        }
    }
}
