package com.neomechanical.neomoderation.moderation;

import com.neomechanical.neomoderation.config.ModerationApiSettings;
import com.neomechanical.neomoderation.testing.TestPlugin;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CloudWiringTest {

    private static final ModerationApiSettings KEY_A = settings("https://api.neomechanical.com/v1/events", "nm_a", 2000);
    private static final String CLAIM = "https://neomechanical.com/signup?trial=t.sig&src=neomoderation_trial";
    private static final TrialClient.TrialStatusResult RUNNING =
            new TrialClient.TrialStatusResult(true, "active", "2026-10-30T00:00:00Z", 10, CLAIM);

    private final ChatModerationCoordinator coordinator = mock(ChatModerationCoordinator.class);
    private final TrialClient client = mock(TrialClient.class);
    private final AtomicReference<ModerationApiSettings> settings = new AtomicReference<>(KEY_A);
    private final List<Runnable> pending = new ArrayList<>();
    @SuppressWarnings("unchecked")
    private final Consumer<Runnable> async = mock(Consumer.class);
    private final List<String> logged = new ArrayList<>();
    private final CloudWiring cloud;

    CloudWiringTest() {
        org.mockito.Mockito.doAnswer(call -> pending.add(call.getArgument(0))).when(async).accept(any());
        cloud = new CloudWiring(coordinator, client, settings::get, async, () -> TestPlugin.NOW, TestPlugin::english,
                () -> mock(CommandSender.class), logged::add);
    }

    private static ModerationApiSettings settings(String endpoint, String key, int readTimeoutMs) {
        return new ModerationApiSettings(endpoint, key, 1500, readTimeoutMs);
    }

    private void load(ModerationApiSettings before, ModerationApiSettings after, boolean reloadEverything) {
        settings.set(after);
        cloud.settingsLoaded(before, after, reloadEverything);
    }

    private void runPending() {
        List<Runnable> tasks = new ArrayList<>(pending);
        pending.clear();
        tasks.forEach(Runnable::run);
    }

    @Test
    void aNewKeyIsUsedForCallsBeforeThePausesResetAndIsAskedAboutLast() throws Exception {
        // Results of calls still in flight for the old key are dropped before the pause resets, or an old key's 401
        // could pause the new one; the watch asks about the key the settings already hold.
        load(null, KEY_A, false);
        verify(coordinator, never()).resetCircuit();
        ModerationApiSettings keyB = settings(KEY_A.endpoint(), "nm_b", 2000);
        load(KEY_A, keyB, false);

        InOrder order = inOrder(coordinator, async);
        order.verify(coordinator).useApi(KEY_A);
        order.verify(async).accept(any());
        order.verify(coordinator).useApi(keyB);
        order.verify(coordinator).resetCircuit();
        order.verify(async).accept(any());
        when(client.fetchTrialStatus(any())).thenReturn(RUNNING);
        runPending();
        ArgumentCaptor<ModerationApiSettings> asked = ArgumentCaptor.forClass(ModerationApiSettings.class);
        verify(client, org.mockito.Mockito.times(2)).fetchTrialStatus(asked.capture());
        assertEquals("nm_b", asked.getAllValues().get(1).apiKey());
    }

    @Test
    void anEditThatLeavesTheKeyEndpointAndTimeoutsAloneKeepsThePausesAndAsksNothing() {
        load(null, KEY_A, false);
        load(KEY_A, settings(KEY_A.endpoint(), "nm_a", 2000), false);
        verify(coordinator, never()).resetCircuit();
        verify(async, org.mockito.Mockito.times(1)).accept(any());
    }

    @Test
    void aNewEndpointOrTimeoutAndNmodReloadResetThePauses() {
        load(null, KEY_A, false);
        load(KEY_A, settings("https://other.example/v1/events", "nm_a", 2000), false);
        load(settings("https://other.example/v1/events", "nm_a", 2000), KEY_A, false);
        load(KEY_A, settings(KEY_A.endpoint(), "nm_a", 3000), false);
        load(settings(KEY_A.endpoint(), "nm_a", 3000), settings(KEY_A.endpoint(), "nm_a", 3000), true);
        verify(coordinator, org.mockito.Mockito.times(4)).resetCircuit();
        verify(async, org.mockito.Mockito.times(1)).accept(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aRefusalIsToldToTheConsoleOnceWithoutTheClaimLinkAndAgainAfterTheKeyWorked() throws Exception {
        ArgumentCaptor<Consumer<ModerationApiResult.Kind>> refusals = ArgumentCaptor.forClass(Consumer.class);
        ArgumentCaptor<Runnable> firstSuccess = ArgumentCaptor.forClass(Runnable.class);
        verify(coordinator).onRefusal(refusals.capture());
        verify(coordinator).onFirstSuccess(firstSuccess.capture());
        when(client.fetchTrialStatus(any())).thenReturn(RUNNING);
        load(null, KEY_A, false);
        runPending();
        assertEquals(List.of(), logged, "a running trial needs no console line");

        refusals.getValue().accept(ModerationApiResult.Kind.INSUFFICIENT_CREDITS);
        runPending();
        assertEquals(List.of("The free cloud trial's credits are used up. Local rules still run. To add credits, first "
                + "keep the trial in a free account. Run /nmod trial status in game and click [Keep cloud moderation]."),
                logged);
        assertFalse(logged.get(0).contains("trial="), "the console's lines are the server log");

        refusals.getValue().accept(ModerationApiResult.Kind.INSUFFICIENT_CREDITS);
        runPending();
        assertEquals(1, logged.size(), "said once, not at every refusal");

        firstSuccess.getValue().run();
        refusals.getValue().accept(ModerationApiResult.Kind.INSUFFICIENT_CREDITS);
        runPending();
        assertEquals(2, logged.size(), "a key that worked again and ran out again is told again");
        assertTrue(logged.get(1).startsWith("The free cloud trial's credits are used up."), logged.get(1));
    }
}
