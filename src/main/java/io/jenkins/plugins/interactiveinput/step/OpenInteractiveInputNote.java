// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.step;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.console.ConsoleAnnotationDescriptor;
import hudson.console.HyperlinkNote;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest2;

/**
 * A console hyperlink that opens a specific interactive-input question's dialog <em>in place</em> on
 * the page that shows the link (B6). On the build's Console Output the shared client ({@code bell.js})
 * intercepts the click and opens the shared native dialog right there, instead of first navigating to
 * the per-build audit page (which then had to auto-open it — an extra hop the reviewer flagged).
 *
 * <p>It extends {@link HyperlinkNote} so the href resolution (context-path aware) and the console-note
 * security envelope (core signs notes; only controller-written notes are honoured on render) are
 * core's. We only contribute, via the {@link #extraAttributes()} hook core provides for exactly this
 * purpose, the two attributes the client needs:
 *
 * <ul>
 *   <li>{@code data-ii-open} — the question id to open;
 *   <li>{@code data-root-url} — the context path, so the REST call resolves under any context path
 *       even when this link is the only interactive-input surface on the page (mirrors the
 *       build-history badge's {@code data-root-url}).
 * </ul>
 *
 * <p>The {@code href} stays a real deep-link ({@code .../interactive-input/?open=<id>}), so the link
 * still works with JavaScript disabled or on a page where {@code bell.js} is not loaded: it then
 * navigates to the audit page, which auto-opens the same dialog. No user-supplied text is emitted —
 * the id is a server-generated UUID and the context path is Jenkins' own — so there is no injection
 * surface beyond what {@link HyperlinkNote} already renders.
 */
public class OpenInteractiveInputNote extends HyperlinkNote {

    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = Logger.getLogger(OpenInteractiveInputNote.class.getName());

    @NonNull
    private final String questionId;

    public OpenInteractiveInputNote(@NonNull String url, @NonNull String questionId, int length) {
        super(url, length);
        this.questionId = questionId;
    }

    /**
     * @return the console string that renders {@code text} as an in-place "open this question" link;
     *     falls back to the plain text if the note cannot be serialised (never breaks the build log).
     */
    @NonNull
    public static String encodeTo(@NonNull String url, @NonNull String questionId, @NonNull String text) {
        try {
            return new OpenInteractiveInputNote(url, questionId, text.length()).encode() + text;
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "failed to serialize OpenInteractiveInputNote", e);
            return text;
        }
    }

    @Override
    protected String extraAttributes() {
        // Resolve the context path the same way HyperlinkNote#annotate resolves the href, so the
        // client's REST base matches the link's origin. Leading space: annotate concatenates this
        // straight after the href attribute's closing quote (…href='…'{extraAttributes}>).
        String contextPath = "";
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        if (req != null) {
            contextPath = req.getContextPath();
        }
        return " class='ii-console-open' data-ii-open='" + Util.escape(questionId) + "' data-root-url='"
                + Util.escape(contextPath) + "'";
    }

    @Extension
    public static class DescriptorImpl extends ConsoleAnnotationDescriptor {
        @NonNull
        @Override
        public String getDisplayName() {
            return "Interactive Input open-dialog console link";
        }
    }
}
