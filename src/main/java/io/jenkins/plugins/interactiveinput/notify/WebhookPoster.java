// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.notify;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ProxyConfiguration;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * POSTs a JSON body to an incoming-webhook URL. http/https only; never logs the URL (it contains a
 * secret) or the response body. Uses {@link ProxyConfiguration#newHttpClient()} so the controller
 * proxy is honoured. Not a Stapler object — no {@code do*} methods.
 */
public final class WebhookPoster {

    private static final Logger LOGGER = Logger.getLogger(WebhookPoster.class.getName());

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private WebhookPoster() {}

    /**
     * Validate and return an http(s) URI, or {@code null} if the scheme/host is not allowed.
     * Package-visible for tests.
     */
    @edu.umd.cs.findbugs.annotations.CheckForNull
    static URI httpUri(@NonNull String raw) {
        String trimmed = raw.trim();
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            LOGGER.log(Level.WARNING, "outbound webhook URL is not a valid URI");
            return null;
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            LOGGER.log(Level.WARNING, "outbound webhook URL is missing a scheme");
            return null;
        }
        String s = scheme.toLowerCase(Locale.ROOT);
        if (!"http".equals(s) && !"https".equals(s)) {
            LOGGER.log(Level.WARNING, "outbound webhook refused: scheme {0} is not http(s)", s);
            return null;
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            LOGGER.log(Level.WARNING, "outbound webhook URL is missing a host");
            return null;
        }
        return uri;
    }

    /**
     * POST {@code json} to {@code webhookUrl}. Failures are logged; this method does not throw.
     *
     * @param webhookUrl the incoming-webhook URL from a Secret-text credential (never logged)
     * @param json JSON object body
     */
    public static void postJson(@NonNull String webhookUrl, @NonNull String json) {
        URI uri = httpUri(webhookUrl);
        if (uri == null) {
            return;
        }
        try {
            HttpClient client = ProxyConfiguration.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            int code = response.statusCode();
            if (code < 200 || code >= 300) {
                LOGGER.log(Level.WARNING, "outbound webhook HTTP {0}", code);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.log(Level.WARNING, "outbound webhook interrupted");
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "outbound webhook failed: {0}", e.toString());
        }
    }
}
