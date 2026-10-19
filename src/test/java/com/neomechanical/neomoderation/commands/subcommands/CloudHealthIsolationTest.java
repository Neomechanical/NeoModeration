package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.config.CaseSettings;
import com.neomechanical.neomoderation.config.MapArtSettings;
import com.neomechanical.neomoderation.config.ModerationApiSettings;
import com.neomechanical.neomoderation.config.ModerationCategorySettings;
import com.neomechanical.neomoderation.config.ModerationMode;
import com.neomechanical.neomoderation.config.ModerationSettings;
import com.neomechanical.neomoderation.config.OfflineModerationSettings;
import com.neomechanical.neomoderation.config.SpamSettings;
import com.neomechanical.neomoderation.config.StrikeSettings;
import com.neomechanical.neomoderation.config.SurfaceSettings;
import com.neomechanical.neomoderation.moderation.ChatModerationCoordinator;
import com.neomechanical.neomoderation.moderation.ModerationApiResult;
import com.neomechanical.neomoderation.testing.TestPlugin;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CloudHealthIsolationTest {
    @Test
    void successfulUsageCheckDoesNotHideBrokenModerationEventsEndpoint() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/events", exchange -> respond(exchange, 422, "bad event request"));
        server.createContext("/v1/workspace/usage", exchange -> respond(exchange, 200,
                "{\"workspace\":\"Test\",\"tier\":\"paid\",\"creditsRemaining\":100}"));
        server.start();

        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/events";
        ModerationSettings settings = settings(endpoint);
        try (ChatModerationCoordinator coordinator = new ChatModerationCoordinator(Logger.getLogger("test"))) {
            coordinator.checkMessage(player(), "hello", settings);
            assertEquals(ModerationApiResult.Kind.CLIENT_REQUEST, coordinator.lastCloudResultKind());

            NeoModerationPlugin plugin = plugin(settings, coordinator);

            new UsageCmd(plugin).execute(mock(CommandSender.class), "nmod", new String[0]);

            assertEquals(ModerationApiResult.Kind.CLIENT_REQUEST, coordinator.lastCloudResultKind());
            assertEquals("status.cloud-request-error",
                    StatusCmd.cloudStatusKey(coordinator.lastCloudResultKind()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void successfulDoctorAccountCheckDoesNotHideBrokenModerationEventsEndpoint() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/events", exchange -> respond(exchange, 422, "bad event request"));
        server.createContext("/v1/workspace/usage", exchange -> respond(exchange, 200,
                "{\"workspace\":\"Test\",\"tier\":\"paid\",\"creditsRemaining\":100}"));
        server.start();

        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/events";
        ModerationSettings settings = settings(endpoint);
        try (ChatModerationCoordinator coordinator = new ChatModerationCoordinator(Logger.getLogger("test"))) {
            coordinator.checkMessage(player(), "hello", settings);
            assertEquals(ModerationApiResult.Kind.CLIENT_REQUEST, coordinator.lastCloudResultKind());

            CommandSender sender = mock(CommandSender.class);
            new DoctorCmd(plugin(settings, coordinator)).execute(sender, "nmod", new String[0]);

            assertEquals(ModerationApiResult.Kind.CLIENT_REQUEST, coordinator.lastCloudResultKind());
            List<String> lines = TestPlugin.lines(sender);
            assertTrue(lines.contains("  Fix Last cloud check: refused; check moderation.api.endpoint"),
                    String.join("\n", lines));
            assertTrue(lines.stream().anyMatch(line -> line.startsWith("  OK Account: ")
                    && line.endsWith("workspace Test, plan paid")), String.join("\n", lines));
            assertTrue(lines.contains("  OK Credits: 100 left"), String.join("\n", lines));
        } finally {
            server.stop(0);
        }
    }

    private static NeoModerationPlugin plugin(ModerationSettings settings, ChatModerationCoordinator coordinator) {
        NeoModerationPlugin plugin = TestPlugin.with(settings);
        when(plugin.coordinator()).thenReturn(coordinator);
        return plugin;
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws java.io.IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private static Player player() {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn("Tester");
        when(player.getUniqueId()).thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        return player;
    }

    private static ModerationSettings settings(String endpoint) {
        return new ModerationSettings(
                true,
                ModerationMode.ENFORCE,
                ModerationMode.ENFORCE,
                // The assertion is about error classification, not a 100 ms cold-client latency limit.
                new ModerationApiSettings(endpoint, "test-key", 1000, 1000),
                new OfflineModerationSettings(true, false, true, List.of(), List.of(), List.of(), List.of()),
                new ModerationCategorySettings(Map.of()),
                new MapArtSettings(true, true, true, true, 1000),
                List.of(),
                true,
                true,
                new ModerationSettings.AlertSettings(true, true),
                new SpamSettings(false, 0, 0, 0.9D, 0, 0, 0, 0),
                new StrikeSettings(false, 30, List.of()),
                new SurfaceSettings(
                        SurfaceSettings.SurfaceMode.OFF,
                        SurfaceSettings.SurfaceMode.OFF,
                        SurfaceSettings.SurfaceMode.OFF,
                        SurfaceSettings.SurfaceMode.OFF,
                        List.of()),
                new CaseSettings(false, false),
                false,
                true
        );
    }
}
