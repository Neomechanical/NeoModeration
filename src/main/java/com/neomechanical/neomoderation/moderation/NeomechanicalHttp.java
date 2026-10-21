package com.neomechanical.neomoderation.moderation;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * How the plugin calls Neomechanical. The moderation API, the trial service and workspace usage share one HTTP client,
 * so one connection pool and one selector thread rather than one each. It never follows a redirect, which would carry
 * the key to wherever it pointed: a redirect reads as a failed call. HTTP/1.1, which every proxy in front of the API
 * speaks, and every request names the plugin ({@link ClientIdentity}).
 */
final class NeomechanicalHttp {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
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

    /**
     * Builds the request and sends it, reading the reply as UTF-8 text, which JSON always is. Every status is an
     * answer, a redirect's too; a request that cannot be built, or that got no reply, throws {@link NoAnswer}.
     */
    static HttpResponse<String> send(Supplier<HttpRequest.Builder> request) throws NoAnswer {
        HttpRequest built;
        try {
            built = request.get().build();
        } catch (IllegalArgumentException invalid) {
            throw new NoAnswer(true, "invalid request");
        }
        try {
            return CLIENT.send(built, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NoAnswer(false, "interrupted");
        } catch (IOException e) {
            throw new NoAnswer(false, "failed");
        }
    }

    /**
     * A call that got no reply: the endpoint or key could not make a request ({@link #invalidRequest()}), or the
     * network, a timeout or an interrupt ended it first.
     */
    static final class NoAnswer extends Exception {
        private final boolean invalidRequest;

        private NoAnswer(boolean invalidRequest, String message) {
            super(message);
            this.invalidRequest = invalidRequest;
        }

        boolean invalidRequest() {
            return invalidRequest;
        }
    }
}
