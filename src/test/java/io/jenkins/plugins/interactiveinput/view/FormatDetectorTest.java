// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.view;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.jenkins.plugins.interactiveinput.view.FormatDetector.Detected;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FormatDetector}, focused on Robot Framework ({@code .robot} / {@code .resource})
 * detection and guarding the existing markdown / code / text mappings against regressions.
 */
class FormatDetectorTest {

    @Test
    void robotSuiteDetectsAsRobotframeworkCode() {
        Detected d = FormatDetector.detect("suite.robot");
        assertEquals(ReviewDocument.FORMAT_CODE, d.format);
        assertEquals("robotframework", d.language);
    }

    @Test
    void robotResourceFileDetectsAsRobotframeworkCode() {
        Detected d = FormatDetector.detect("keywords.resource");
        assertEquals(ReviewDocument.FORMAT_CODE, d.format);
        assertEquals("robotframework", d.language);
    }

    @Test
    void robotExtensionIsCaseInsensitive() {
        Detected d = FormatDetector.detect("SUITE.ROBOT");
        assertEquals(ReviewDocument.FORMAT_CODE, d.format);
        assertEquals("robotframework", d.language);
    }

    @Test
    void robotDetectionUsesExtensionEvenWithNestedPath() {
        Detected d = FormatDetector.detect("tests/acceptance/login.robot");
        assertEquals(ReviewDocument.FORMAT_CODE, d.format);
        assertEquals("robotframework", d.language);
    }

    @Test
    void explicitTextOverrideBeatsRobotExtension() {
        // A user-supplied format:'text' must win over extension detection.
        Detected d = FormatDetector.resolve("text", "suite.robot");
        assertEquals(ReviewDocument.FORMAT_TEXT, d.format);
        assertEquals("none", d.language);
    }

    @Test
    void explicitCodeOverrideKeepsRobotframeworkLanguage() {
        Detected d = FormatDetector.resolve("code", "suite.robot");
        assertEquals(ReviewDocument.FORMAT_CODE, d.format);
        assertEquals("robotframework", d.language);
    }

    // ---- regression guards: unrelated mappings must be unchanged ----

    @Test
    void markdownStillDetected() {
        Detected d = FormatDetector.detect("notes.md");
        assertEquals(ReviewDocument.FORMAT_MARKDOWN, d.format);
        assertEquals("markdown", d.language);
    }

    @Test
    void pythonStillDetectedAsCode() {
        Detected d = FormatDetector.detect("script.py");
        assertEquals(ReviewDocument.FORMAT_CODE, d.format);
        assertEquals("python", d.language);
    }

    @Test
    void plainTextStillDetected() {
        Detected d = FormatDetector.detect("readme.txt");
        assertEquals(ReviewDocument.FORMAT_TEXT, d.format);
        assertEquals("none", d.language);
    }

    @Test
    void unknownExtensionStillFallsBackToText() {
        Detected d = FormatDetector.detect("data.unknownext");
        assertEquals(ReviewDocument.FORMAT_TEXT, d.format);
        assertEquals("none", d.language);
    }
}
