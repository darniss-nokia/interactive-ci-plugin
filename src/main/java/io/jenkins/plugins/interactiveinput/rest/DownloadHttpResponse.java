// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.rest;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Util;
import java.io.IOException;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;

/**
 * An {@link HttpResponse} that streams a byte payload as a browser file download (Interactive View
 * download feature, item #1).
 *
 * <p>The binary counterpart of {@link JsonHttpResponse}. It always sets:
 *
 * <ul>
 *   <li>{@code Content-Disposition: attachment} with <em>both</em> an ASCII-sanitised
 *       {@code filename="…"} and an RFC&nbsp;5987 {@code filename*=UTF-8''…}, so a non-ASCII name still
 *       downloads correctly while the header itself can never be broken by a quote, backslash or CR/LF
 *       smuggled into the file name (header-injection defence);</li>
 *   <li>{@code X-Content-Type-Options: nosniff} so the browser never MIME-sniffs the bytes into
 *       something executable; and</li>
 *   <li>{@code Cache-Control: no-store} (a review snapshot may change between versions).</li>
 * </ul>
 *
 * <p>The payload is prepared in memory by the caller. The {@code interactiveView} step caps a single
 * snapshot at 2&nbsp;MB and a published group at 8&nbsp;MB, so both a single-file download and a
 * whole-group ZIP stay comfortably bounded.
 */
public class DownloadHttpResponse implements HttpResponse {

    @NonNull
    private final String contentType;

    @NonNull
    private final String downloadName;

    @NonNull
    private final byte[] body;

    /**
     * @param contentType  the response {@code Content-Type} (e.g. {@code application/octet-stream} for a
     *                     single file, {@code application/zip} for a group archive)
     * @param downloadName the suggested file name (sanitised into the header by {@link #contentDisposition})
     * @param body         the bytes to stream (copied defensively so the caller cannot mutate them later)
     */
    public DownloadHttpResponse(@NonNull String contentType, @NonNull String downloadName, @NonNull byte[] body) {
        this.contentType = contentType;
        this.downloadName = downloadName;
        this.body = body.clone();
    }

    @Override
    public void generateResponse(StaplerRequest2 req, StaplerResponse2 rsp, Object node) throws IOException {
        rsp.setStatus(200);
        rsp.setContentType(contentType);
        rsp.addHeader("Content-Disposition", contentDisposition(downloadName));
        rsp.addHeader("X-Content-Type-Options", "nosniff");
        rsp.addHeader("Cache-Control", "no-store");
        rsp.setContentLength(body.length);
        rsp.getOutputStream().write(body);
    }

    /**
     * Builds a safe {@code attachment} Content-Disposition value with an ASCII {@code filename} and an
     * RFC&nbsp;5987 {@code filename*}. Control characters (incl. CR/LF), quotes and backslashes are
     * stripped from the ASCII form so the header is always well-formed; the UTF-8 form is percent-encoded
     * so a non-ASCII name is still conveyed.
     */
    @NonNull
    static String contentDisposition(@NonNull String name) {
        StringBuilder ascii = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c >= 0x20 && c < 0x7f && c != '"' && c != '\\') {
                ascii.append(c);
            } else {
                ascii.append('_'); // control / quote / backslash / non-ASCII -> safe placeholder
            }
        }
        String asciiName = ascii.toString().trim();
        if (asciiName.isEmpty()) {
            asciiName = "download";
        }
        return "attachment; filename=\"" + asciiName + "\"; filename*=UTF-8''" + Util.rawEncode(name);
    }
}
