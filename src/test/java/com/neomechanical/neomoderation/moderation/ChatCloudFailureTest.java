package com.neomechanical.neomoderation.moderation;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.config.BukkitConfigView;
import com.neomechanical.neomoderation.config.ModerationMode;
import com.neomechanical.neomoderation.config.ModerationSettings;
import com.neomechanical.neomoderation.messages.MessageService;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ChatCloudFailureTest {
    @ParameterizedTest(name = "{0}, failOpen={1}, cloudMode={2}")
    @MethodSource("cloudResultsAndPolicies")
    void cloudResultsFollowFailurePolicyWithoutAccusingPlayersDuringOutages(
            ModerationApiResult.Kind kind, boolean failOpen, ModerationMode cloudMode) {
        ModerationSettings settings = settings(failOpen, cloudMode);
        NeoModerationPlugin plugin = mock(NeoModerationPlugin.class);
        MessageService messages = mock(MessageService.class);
        when(plugin.messages()).thenReturn(messages);
        when(plugin.settings()).thenReturn(settings);
        ChatModerationCoordinator coordinator = mock(ChatModerationCoordinator.class);
        DetectionHandler handler = mock(DetectionHandler.class);
        Player player = mock(Player.class);
        when(player.getName()).thenReturn("Tester");
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        ModerationApiResult cloudResult = kind == ModerationApiResult.Kind.FLAGGED
                ? ModerationApiResult.flagged("harassment") : new ModerationApiResult(kind);
        when(coordinator.checkMessage(player, "hello", settings)).thenReturn(cloudResult);
        doAnswer(call -> {
            call.<Consumer<ModerationApiResult>>getArgument(3).accept(cloudResult);
            return null;
        }).when(coordinator).checkInBackground(eq(player), eq("hello"), eq(settings), any());
        // A decision that can only alert is asked for as one.
        DetectionHandler.Disposition requested = cloudMode == ModerationMode.ENFORCE
                ? DetectionHandler.Disposition.BLOCK : DetectionHandler.Disposition.ALLOW;
        if (cloudResult.isFlagged()) {
            when(handler.handle(player, "chat", "platform:harassment", "hello",
                    requested, DetectionHandler.Source.CLOUD))
                    .thenReturn(requested);
        }

        ChatDecision result = new ChatModerationProcessor(plugin, coordinator,
                mock(PlayerMuteService.class), new SpamDetector(), handler).handleChat(player, "hello", true);

        boolean unavailable = kind != ModerationApiResult.Kind.CLEAR && !cloudResult.isFlagged();
        boolean unavailableBlocked = unavailable && !failOpen && cloudMode == ModerationMode.ENFORCE;
        boolean blocked = unavailableBlocked || (cloudResult.isFlagged() && cloudMode == ModerationMode.ENFORCE);
        assertEquals(blocked ? ChatDecision.block() : ChatDecision.allow(), result);
        if (cloudMode == ModerationMode.MONITOR) {
            // A decision that can only alert never holds the message: it is checked in the background.
            verify(coordinator).checkInBackground(eq(player), eq("hello"), eq(settings), any());
            verify(coordinator, never()).checkMessage(any(), anyString(), any());
        } else {
            verify(coordinator).checkMessage(player, "hello", settings);
        }
        if (cloudResult.isFlagged()) {
            verify(handler).handle(player, "chat", "platform:harassment", "hello",
                    requested, DetectionHandler.Source.CLOUD);
            verifyNoMoreInteractions(handler);
        } else {
            verifyNoInteractions(handler);
        }
        if (unavailableBlocked) {
            verify(messages).send(player, "chat.cloud-unavailable");
            verifyNoMoreInteractions(messages);
        } else {
            verifyNoInteractions(messages);
        }
    }

    private static Stream<Arguments> cloudResultsAndPolicies() {
        return Stream.of(ModerationApiResult.Kind.values()).flatMap(kind ->
                Stream.of(true, false).flatMap(failOpen ->
                        Stream.of(ModerationMode.ENFORCE, ModerationMode.MONITOR)
                                .map(mode -> Arguments.of(kind, failOpen, mode))));
    }

    private ModerationSettings settings(boolean failOpen, ModerationMode cloudMode) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("moderation.enabled", true);
        config.set("moderation.api.apiKey", "test-key");
        config.set("moderation.offline.enabled", false);
        config.set("moderation.spam.enabled", false);
        config.set("moderation.cloudMode", cloudMode.name());
        config.set("moderation.chat.failOpen", failOpen);
        return ModerationSettings.from(new BukkitConfigView(config));
    }

    @org.junit.jupiter.api.Test
    void chatFromTheServerThreadNeverWaitsForTheCloudAndCanOnlyAlert() {
        ModerationSettings settings = settings(false, ModerationMode.ENFORCE);
        NeoModerationPlugin plugin = mock(NeoModerationPlugin.class);
        when(plugin.settings()).thenReturn(settings);
        ChatModerationCoordinator coordinator = mock(ChatModerationCoordinator.class);
        DetectionHandler handler = mock(DetectionHandler.class);
        Player player = mock(Player.class);
        when(player.getName()).thenReturn("Tester");
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        doAnswer(call -> {
            call.<Consumer<ModerationApiResult>>getArgument(3).accept(ModerationApiResult.flagged("harassment"));
            return null;
        }).when(coordinator).checkInBackground(eq(player), eq("hello"), eq(settings), any());

        ChatDecision decision = new ChatModerationProcessor(plugin, coordinator,
                mock(PlayerMuteService.class), new SpamDetector(), handler).handleChat(player, "hello", false);

        assertEquals(ChatDecision.allow(), decision);
        verify(coordinator, never()).checkMessage(any(), anyString(), any());
        verify(handler).handle(player, "chat", "platform:harassment", "hello",
                DetectionHandler.Disposition.ALLOW, DetectionHandler.Source.CLOUD);
    }

    @org.junit.jupiter.api.Test
    void shoutingGoesOnInLowerCaseWithoutBeingBlockedOrReported() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("moderation.enabled", true);
        config.set("moderation.offline.bannedWords", java.util.List.of("badword"));
        ModerationSettings settings = ModerationSettings.from(new BukkitConfigView(config));
        NeoModerationPlugin plugin = mock(NeoModerationPlugin.class);
        when(plugin.settings()).thenReturn(settings);
        DetectionHandler handler = mock(DetectionHandler.class);
        Player player = mock(Player.class);
        when(player.getName()).thenReturn("Tester");
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());

        ChatDecision decision = new ChatModerationProcessor(plugin, mock(ChatModerationCoordinator.class),
                mock(PlayerMuteService.class), new SpamDetector(), handler).handleChat(player, "WHO WANTS TO TRADE", true);

        assertEquals(ChatDecision.censor("who wants to trade"), decision);
        verifyNoInteractions(handler);
    }
}
