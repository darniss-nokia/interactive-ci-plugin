// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.rest;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;

/**
 * An {@link HttpResponse} that writes a JSON body with an explicit HTTP status code.
 *
 * <p>All REST responses use this so both success and error payloads are valid JSON (§8.6), letting
 * external agents parse errors uniformly instead of receiving Jenkins' HTML error pages.
 */
public class JsonHttpResponse implements HttpResponse {

    private final int status;

    @NonNull
    private final String body;

    public JsonHttpResponse(int status, @NonNull JSONObject body) {
        this.status = status;
        this.body = body.toString();
    }

    /** Convenience factory for a JSON error envelope: {@code {"error": true, "message": ...}}. */
    @NonNull
    public static JsonHttpResponse error(int status, @NonNull String message) {
        JSONObject o = new JSONObject();
        o.put("error", true);
        o.put("status", status);
        o.put("message", message);
        return new JsonHttpResponse(status, o);
    }

    @Override
    public void generateResponse(StaplerRequest2 req, StaplerResponse2 rsp, Object node) throws IOException {
        rsp.setStatus(status);
        rsp.setContentType("application/json;charset=UTF-8");
        rsp.addHeader("Cache-Control", "no-store");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        rsp.setContentLength(bytes.length);
        rsp.getOutputStream().write(bytes);
    }
}
