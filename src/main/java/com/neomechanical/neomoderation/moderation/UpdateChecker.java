package com.neomechanical.neomoderation.moderation;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Tells an operator, once per start, that a newer release exists.
 *
 * <p>Without this an install never learns anything improved. On 2026-09-03 a
 * server was still running 1.5.0 six days after 1.5.1 fixed a matcher that
 * missed 33 of 51 evasions, with nothing anywhere to tell its owner. Word-list
 * moderation ages badly -- the evasions people use change -- so a silent old
 * install is a quietly failing one.
 *
 * <p>Deliberately minimal: one GET to the public releases API, no data sent, no
 * download, no nag beyond a single console line. The version comparison is
 * numeric-segment-wise so 1.10.0 correctly beats 1.9.0.
 */
public final class UpdateChecker {

    public static final String RELEASES_API =
            "https://api.github.com/repos/Neomechanical/NeoModeration/releases/latest";
    public static final String RELEASES_PAGE =
            "https://github.com/Neomechanical/NeoModeration/releases/latest";

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private UpdateChecker() {
    }

    /**
     * Blocking; call from an async task. Returns the newer version when one
     * exists, otherwise empty. Every failure is empty -- an update check must
     * never be why a server sees a stack trace on boot.
     */
    public static Optional<String> findNewerVersion(String currentVersion, String userAgent) {
        try {
            // The repository moved once already; GitHub answers the old address with a
            // redirect, which an HTTP client that never follows one reads as "no update".
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(RELEASES_API))
                    .timeout(TIMEOUT)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", userAgent)
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return Optional.empty();
            }
            JsonObject payload = CloudResponseJson.parseObject(response.body());
            JsonElement tagElement = payload == null ? null : payload.get("tag_name");
            if (tagElement == null || !tagElement.isJsonPrimitive()) {
                return Optional.empty();
            }
            String latest = stripLeadingV(tagElement.getAsString());
            return isNewer(latest, currentVersion) ? Optional.of(latest) : Optional.empty();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    /** The console lines to print for a newer release. */
    public static List<String> updateLines(String latest, String current) {
        return List.of(
                "A newer NeoModeration is available: " + latest + " (this server runs " + current + ").",
                "Chat evasions change over time, so an old matcher quietly misses more. "
                        + "Download: " + RELEASES_PAGE
        );
    }

    static String stripLeadingV(String tag) {
        String trimmed = tag == null ? "" : tag.trim();
        return trimmed.startsWith("v") || trimmed.startsWith("V") ? trimmed.substring(1) : trimmed;
    }

    /** True when {@code candidate} is a strictly higher version than {@code current}. */
    static boolean isNewer(String candidate, String current) {
        int[] left = parse(candidate);
        int[] right = parse(current);
        int length = Math.max(left.length, right.length);
        for (int index = 0; index < length; index++) {
            int a = index < left.length ? left[index] : 0;
            int b = index < right.length ? right[index] : 0;
            if (a != b) {
                return a > b;
            }
        }
        return false;
    }

    private static int[] parse(String version) {
        if (version == null || version.isBlank()) {
            return new int[0];
        }
        // Drop any -SNAPSHOT / -rc1 suffix before comparing numeric segments.
        String core = version.trim().split("[-+]", 2)[0];
        String[] segments = core.split("\\.");
        int[] values = new int[segments.length];
        for (int index = 0; index < segments.length; index++) {
            try {
                values[index] = Integer.parseInt(segments[index].trim());
            } catch (NumberFormatException ignored) {
                values[index] = 0;
            }
        }
        return values;
    }
}
