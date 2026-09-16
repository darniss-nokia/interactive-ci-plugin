// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.URI;
import org.junit.jupiter.api.Test;

class WebhookPosterTest {

    @Test
    void rejectsNonHttpSchemes() {
        assertNull(WebhookPoster.httpUri("file:///etc/passwd"));
        assertNull(WebhookPoster.httpUri("javascript:alert(1)"));
        assertNull(WebhookPoster.httpUri("ftp://example.com/hook"));
        assertNull(WebhookPoster.httpUri("not a uri"));
        assertNull(WebhookPoster.httpUri("http:///nohost"));
    }

    @Test
    void acceptsHttpAndHttps() {
        URI http = WebhookPoster.httpUri("http://127.0.0.1:9/hook");
        assertNotNull(http);
        assertEquals("http", http.getScheme());
        URI https = WebhookPoster.httpUri("https://hooks.example.com/services/xxx");
        assertNotNull(https);
        assertEquals("https", https.getScheme());
    }
}
