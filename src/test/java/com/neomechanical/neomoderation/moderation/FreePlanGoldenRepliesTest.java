package com.neomechanical.neomoderation.moderation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The platform's golden replies (its config/free-plan/golden-replies.json, copied here as
 * src/test/resources/free-plan/golden-replies.json): the shape of every reply this version reads, each field's name and
 * type. A sample reply is built from each shape, with the values whose meaning matters (the plan, the status, the code)
 * taken from the entry, and this version's own parsers read it. A field the platform renames or retypes changes the
 * copy, and this test then fails here before any server meets it. Refresh the copy from the platform when it changes.
 */
class FreePlanGoldenRepliesTest {

    private static final Pattern CODE = Pattern.compile("\\b(free_plan_[a-z_]+|ip_trial_[a-z_]+|install_mismatch)\\b");

    private static JsonObject golden() throws IOException {
        try (InputStream in = FreePlanGoldenRepliesTest.class.getResourceAsStream("/free-plan/golden-replies.json")) {
            assertNotNull(in, "the pinned copy of the platform's golden replies");
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    /** A reply of the entry's shape: each type a value, with the values that carry meaning set from the entry. */
    private static JsonObject sample(String entry, JsonObject shape, Map<String, JsonElement> meaning) {
        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> field : shape.entrySet()) {
            String key = field.getKey();
            JsonElement type = field.getValue();
            if (meaning.containsKey(key)) {
                out.add(key, meaning.get(key));
            } else if (type.isJsonObject()) {
                out.add(key, sample(entry, type.getAsJsonObject(), meaning));
            } else if (type.isJsonArray()) {
                out.add(key, new JsonArray());
            } else {
                out.add(key, switch (type.getAsString()) {
                    case "string" -> new JsonPrimitive(key.endsWith("At") ? "2026-11-02T00:00:00Z"
                            : key.endsWith("Url") ? "https://neomechanical.com/signup?trial=v2.x" : "text");
                    case "number" -> new JsonPrimitive(key.equals("checksToday") ? 7 : 120);
                    case "boolean" -> new JsonPrimitive(false);
                    default -> com.google.gson.JsonNull.INSTANCE;
                });
            }
        }
        return out;
    }

    private static String codeOf(String entry) {
        Matcher matcher = CODE.matcher(entry);
        return matcher.find() ? matcher.group(1) : "";
    }

    @Test
    void theCopyHoldsEveryReplyThisVersionReads() throws IOException {
        Set<String> entries = new TreeSet<>(golden().keySet());
        for (String needed : new String[]{"activation 201 free plan", "activation 201 trial",
                "activation 429 free_plan_closed", "activation 429 ip_trial_limit_reached (the trial's 429)",
                "conversion 200", "conversion 403 install_mismatch", "status 200 free plan, unclaimed",
                "status 200 free plan, claimed", "status 200 trial, free plan open", "429 free_plan_daily_cap",
                "429 free_plan_image_allowance", "429 free_plan_busy (budget, at admission)",
                "intake 400 free_plan_request_shape"}) {
            assertTrue(entries.contains(needed), "missing " + needed + " in " + entries);
        }
    }

    @Test
    void activationRepliesKeepTheKeyAndSayWhichPlan() throws Exception {
        JsonObject golden = golden();
        JsonObject free = sample("activation 201 free plan", golden.getAsJsonObject("activation 201 free plan")
                .getAsJsonObject("body"), Map.of("plan", new JsonPrimitive("free"), "status", new JsonPrimitive("free_plan"),
                "apiKey", new JsonPrimitive("nm_free")));
        TrialClient.TrialResult started = TrialClient.parseSuccess(free.toString());
        assertEquals("nm_free", started.apiKey());
        assertTrue(started.onFreePlan(), "the free plan's activation");
        assertEquals(120, started.freePlan().dailyChecks());
        assertEquals("", started.expiresAt(), "a free plan has no end");

        JsonObject trial = sample("activation 201 trial", golden.getAsJsonObject("activation 201 trial")
                .getAsJsonObject("body"), Map.of("apiKey", new JsonPrimitive("nm_trial")));
        TrialClient.TrialResult trialStarted = TrialClient.parseSuccess(trial.toString());
        assertFalse(trialStarted.onFreePlan());
        assertEquals(120, trialStarted.daysRemaining());
    }

    @Test
    void statusRepliesReadAsTheirPlan() throws Exception {
        JsonObject golden = golden();
        for (String entry : new String[]{"status 200 free plan, unclaimed", "status 200 free plan, claimed", "conversion 200"}) {
            JsonObject body = sample(entry, golden.getAsJsonObject(entry).getAsJsonObject("body"), Map.of(
                    "plan", new JsonPrimitive("free"), "status", new JsonPrimitive("free_plan"),
                    "isTrial", new JsonPrimitive(false)));
            TrialClient.TrialStatusResult status = TrialClient.parseStatusSuccess(body.toString());
            assertTrue(status.onFreePlan(), entry);
            assertEquals(CloudPlanWatch.State.FREE_PLAN, CloudPlanWatch.classify(status), entry);
            assertEquals(7, status.freePlan().checksToday(), entry);
        }
        JsonObject trial = sample("status 200 trial, free plan open", golden.getAsJsonObject("status 200 trial, free plan open")
                .getAsJsonObject("body"), Map.of("isTrial", new JsonPrimitive(true), "status", new JsonPrimitive("active"),
                "freePlanOpen", new JsonPrimitive(true)));
        TrialClient.TrialStatusResult running = TrialClient.parseStatusSuccess(trial.toString());
        assertTrue(running.freePlanOpen(), "the offer's flag is read");
        assertEquals(CloudPlanWatch.State.TRIAL_ACTIVE, CloudPlanWatch.classify(running));
    }

    @Test
    void activationAndConversionRefusalsAreReadByTheirCode() throws IOException {
        JsonObject golden = golden();
        Map<String, TrialClient.TrialError> expected = Map.of(
                "activation 429 free_plan_closed", TrialClient.TrialError.FREE_PLAN_CLOSED,
                "activation 429 ip_trial_limit_reached (the trial's 429)", TrialClient.TrialError.COOLDOWN,
                "conversion 403 install_mismatch", TrialClient.TrialError.REFUSED);
        for (Map.Entry<String, TrialClient.TrialError> entry : expected.entrySet()) {
            JsonObject reply = golden.getAsJsonObject(entry.getKey());
            String code = codeOf(entry.getKey());
            JsonObject body = sample(entry.getKey(), reply.getAsJsonObject("body"), Map.of("error", new JsonPrimitive(code)));
            TrialClient.TrialException refusal = TrialClient.refusal(reply.get("status").getAsInt(), body.toString(), 0L);
            assertEquals(entry.getValue(), refusal.error(), entry.getKey());
            assertEquals(code, refusal.code(), entry.getKey());
            if (body.has("retryAfterSeconds")) {
                assertEquals(120, refusal.retryAfterSeconds(), entry.getKey());
            }
        }
    }

    @Test
    void cloudCheckRefusalsAreReadByTheirCodeAndNeverAsAnOutage() throws IOException {
        JsonObject golden = golden();
        Map<String, ModerationApiResult.Kind> expected = Map.of(
                "429 free_plan_daily_cap", ModerationApiResult.Kind.FREE_PLAN_CAPPED,
                "429 free_plan_image_allowance", ModerationApiResult.Kind.FREE_PLAN_PICTURES,
                "429 free_plan_busy (budget, at admission)", ModerationApiResult.Kind.FREE_PLAN_BUSY,
                "429 free_plan_busy (kill switch, at intake)", ModerationApiResult.Kind.FREE_PLAN_BUSY,
                "intake 400 free_plan_request_shape", ModerationApiResult.Kind.FREE_PLAN_REQUEST_SHAPE);
        for (Map.Entry<String, ModerationApiResult.Kind> entry : expected.entrySet()) {
            JsonObject reply = golden.getAsJsonObject(entry.getKey());
            JsonObject body = sample(entry.getKey(), reply.getAsJsonObject("body"),
                    Map.of("code", new JsonPrimitive(codeOf(entry.getKey()))));
            ModerationApiResult result = ModerationApiClient.refusal(reply.get("status").getAsInt(), body.toString(), 0L);
            assertEquals(entry.getValue(), result.kind(), entry.getKey());
            assertFalse(result.tripsTransientBreaker(), entry.getKey() + " never reads as an outage");
            if (body.has("retryAfterSeconds")) {
                assertEquals(120, result.retryAfterSeconds(), entry.getKey() + " waits as long as it was asked");
            }
        }
        // Released plugins get the picture allowance as a 422; this version reads it the same, by its code.
        assertEquals(ModerationApiResult.Kind.FREE_PLAN_PICTURES, ModerationApiClient.refusal(422,
                "{\"code\":\"free_plan_image_allowance\",\"error\":\"x\"}", 0L).kind());
    }
}
