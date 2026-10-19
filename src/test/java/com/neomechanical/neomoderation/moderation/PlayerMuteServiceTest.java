package com.neomechanical.neomoderation.moderation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerMuteServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void mutesAndExpires() throws InterruptedException {
        try (PlayerMuteService service = new PlayerMuteService(tempDir.resolve("mutes.yml").toFile())) {
            UUID playerId = UUID.randomUUID();

            service.mute(playerId, 1);
            assertTrue(service.isMuted(playerId));
            assertTrue(service.remainingSeconds(playerId) >= 1);

            Thread.sleep(1100);
            assertFalse(service.isMuted(playerId));
            assertTrue(service.remainingSeconds(playerId) == 0);
        }
    }

    @Test
    void unmuteClearsMute() {
        try (PlayerMuteService service = new PlayerMuteService(tempDir.resolve("mutes.yml").toFile())) {
            UUID playerId = UUID.randomUUID();
            service.mute(playerId, 60);
            service.unmute(playerId);
            assertFalse(service.isMuted(playerId));
        }
    }

    @Test
    void persistsAcrossReload() {
        File file = tempDir.resolve("mutes.yml").toFile();
        UUID playerId = UUID.randomUUID();
        PlayerMuteService first = new PlayerMuteService(file);
        first.mute(playerId, 120);
        first.close();

        try (PlayerMuteService second = new PlayerMuteService(file)) {
            assertTrue(second.isMuted(playerId));
        }
    }
}
