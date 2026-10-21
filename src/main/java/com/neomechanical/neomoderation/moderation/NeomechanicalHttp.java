package com.neomechanical.neomoderation.moderation;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;

/**
 * How the plugin calls Neomechanical. The moderation API, the trial service and workspace usage share one HTTP client,
 * so one connection pool and one selector thread rather than one each. It never follows a redirect, which would carry
 * the key to wherever it pointed: a redirect reads as a failed call. HTTP/1.1, which every proxy in front of the API
 * speaks, and every request names the plugin ({@link ClientIdentity}).
 */
final class NeomechanicalHttp {

    static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private NeomechanicalHttp() {
    }

    /** A request to {@code url}, answered within {@code timeout}; IllegalArgumentException when it is no URL. */
    static HttpRequest.Builder request(String url, Duration timeout) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("User-Agent", ClientIdentity.userAgent());
    }

    /** As {@link #request}, with the server's API key. */
    static HttpRequest.Builder withKey(String url, Duration timeout, String apiKey) {
        return request(url, timeout).header("Authorization", "Bearer " + apiKey);
    }
}
