// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class EmailRecipientsTest {

    @Test
    void splitsCommaAndSemicolonAndDropsInjection() {
        List<String> got = EmailRecipients.parse("a@example.com, b@example.com; c@example.com");
        assertEquals(List.of("a@example.com", "b@example.com", "c@example.com"), got);
        assertTrue(EmailRecipients.parse("evil\r\nBcc: x@x.com").isEmpty()
                || EmailRecipients.parse("evil\r\nBcc: x@x.com").stream()
                        .noneMatch(a -> a.contains("\n") || a.contains("\r")));
        assertTrue(EmailRecipients.parse("not-an-email").isEmpty());
        assertTrue(EmailRecipients.parse("").isEmpty());
        assertTrue(EmailRecipients.parse("a@b.com\nbad@b.com").stream().noneMatch(a -> a.contains("\n")));
    }

    @Test
    void sanitiseStripsCrLf() {
        assertEquals("a@b.com", EmailRecipients.sanitise(" a@b.com\r\n "));
        assertNull(EmailRecipients.sanitise("nope"));
        assertNull(EmailRecipients.sanitise("<a@b.com>"));
    }
}
