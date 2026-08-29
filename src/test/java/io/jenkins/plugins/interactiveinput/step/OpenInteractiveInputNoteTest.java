// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.step;

import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.MarkupText;
import org.junit.jupiter.api.Test;

/**
 * Unit guard for the B6 console note: it must render the "Open interactive input" text as an anchor
 * carrying the attributes {@code bell.js} keys off to open the dialog in place — the marker class, the
 * question id ({@code data-ii-open}) and the context path ({@code data-root-url}).
 */
class OpenInteractiveInputNoteTest {

    @Test
    void rendersInPlaceOpenAnchor() {
        MarkupText text = new MarkupText("Open interactive input");
        // A non-rooted url keeps HyperlinkNote#annotate from touching Stapler/Jenkins; with no active
        // request extraAttributes() records an empty context path. The class + data-* attributes are what
        // the client keys off, so those are what this guards.
        new OpenInteractiveInputNote("job/x/1/interactive-input/?open=q-42", "q-42", text.length())
                .annotate(null, text, 0);

        String html = text.toString(true);
        assertTrue(html.contains("class='ii-console-open'"), html);
        assertTrue(html.contains("data-ii-open='q-42'"), html);
        assertTrue(html.contains("data-root-url='"), html);
        assertTrue(html.contains(">Open interactive input</a>"), html);
    }
}
