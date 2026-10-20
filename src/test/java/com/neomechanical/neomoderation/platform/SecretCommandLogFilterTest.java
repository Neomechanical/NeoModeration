package com.neomechanical.neomoderation.platform;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretCommandLogFilterTest {
    @Test
    void commandsThatCarryASecretAreKeptOutOfTheLog() {
        for (String line : List.of(
                "Steve issued server command: /nmod setup nm_live_abc123",
                "Steve issued server command: /NMOD key set nm_live_abc123",
                "Steve issued server command: /neomod discord https://discord.com/api/webhooks/1/abc",
                "Steve issued server command: /neomoderation:nmod setup nm_live_abc123")) {
            assertTrue(SecretCommandLogFilter.hides(line), line);
        }
    }

    @Test
    void everythingElseIsLoggedAsUsual() {
        for (String line : List.of(
                "Steve issued server command: /nmod setup",
                "Steve issued server command: /nmod key clear",
                "Steve issued server command: /nmod discord test",
                "Steve issued server command: /nmod discord off",
                "Steve issued server command: /nmod status",
                "Steve issued server command: /msg Alex setup nm_live_abc123",
                "[NeoModeration] Blocked Steve in chat: /nmod setup nm_live_abc123 (banned word \"x\")")) {
            assertFalse(SecretCommandLogFilter.hides(line), line);
        }
    }

    /** Keeps every line the server's logger writes. */
    private static final class Lines extends AbstractAppender {
        private final List<String> lines = new ArrayList<>();

        Lines() {
            super("lines", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            lines.add(event.getMessage().getFormattedMessage());
        }
    }

    @Test
    void theFilterSitsOnTheServersLoggerUntilThePluginStops() {
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        Lines appender = new Lines();
        appender.start();
        Level level = context.getConfiguration().getRootLogger().getLevel();
        // Log4j's own default writes only errors; a server writes INFO.
        context.getConfiguration().getRootLogger().setLevel(Level.INFO);
        context.getConfiguration().getRootLogger().addAppender(appender, null, null);
        context.updateLoggers();
        Logger server = LogManager.getLogger("Minecraft");
        String secret = "Steve issued server command: /nmod key set nm_live_abc123";
        String ordinary = "Steve issued server command: /nmod status";
        try {
            assertTrue(SecretCommandLogFilter.install());
            server.info(secret);
            server.info(ordinary);
            assertEquals(List.of(ordinary), appender.lines, "the key stays out while the plugin runs");

            SecretCommandLogFilter.uninstall();
            server.info(secret);
            assertEquals(List.of(ordinary, secret), appender.lines, "a stopped plugin leaves the logger as it was");
        } finally {
            SecretCommandLogFilter.uninstall();
            context.getConfiguration().getRootLogger().removeAppender("lines");
            context.getConfiguration().getRootLogger().setLevel(level);
            context.updateLoggers();
        }
    }
}
