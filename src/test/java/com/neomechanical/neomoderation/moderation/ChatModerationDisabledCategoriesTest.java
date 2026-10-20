package com.neomechanical.neomoderation.moderation;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.config.BukkitConfigView;
import com.neomechanical.neomoderation.config.ModerationCategorySettings;
import com.neomechanical.neomoderation.config.ModerationSettings;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatModerationDisabledCategoriesTest {
    @Test
    void allCloudTextCategoriesOffMeansNoChargeableChatRequest() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("moderation.enabled", true);
        config.set("moderation.offline.enabled", false);
        config.set("moderation.spam.enabled", false);
        config.set("moderation.api.apiKey", "test-key");
        for (String category : ModerationCategorySettings.categoryKeys()) {
            config.set("moderation.categories." + category, false);
        }
        ModerationSettings settings = ModerationSettings.from(new BukkitConfigView(config));
        NeoModerationPlugin plugin = mock(NeoModerationPlugin.class);
        when(plugin.settings()).thenReturn(settings);
        ChatModerationCoordinator coordinator = mock(ChatModerationCoordinator.class);
        PlayerMuteService muteService = mock(PlayerMuteService.class);
        SpamDetector spamDetector = mock(SpamDetector.class);
        when(spamDetector.checkMessage(any(), anyString(), anyString(), any(), anyLong()))
                .thenReturn(java.util.Optional.empty());
        DetectionHandler handler = mock(DetectionHandler.class);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
        when(player.getName()).thenReturn("Tester");

        ChatDecision result = new ChatModerationProcessor(plugin, coordinator, muteService,
                spamDetector, handler).handleChat(player, "hello", true);

        assertEquals(ChatDecision.allow(), result);
        verify(coordinator, never()).checkMessage(any(Player.class), anyString(), any(ModerationSettings.class));
    }
}
