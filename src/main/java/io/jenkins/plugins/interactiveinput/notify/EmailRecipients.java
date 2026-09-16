// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.notify;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits and sanitises a comma/semicolon-separated recipient list. Rejects CR/LF (header injection)
 * and obviously non-email tokens. Does not try to be a full RFC 5322 parser.
 */
public final class EmailRecipients {

    private EmailRecipients() {}

    @NonNull
    public static List<String> parse(@CheckForNull String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String part : raw.split("[,;]")) {
            String addr = sanitise(part);
            if (addr != null) {
                out.add(addr);
            }
        }
        return out;
    }

    @CheckForNull
    static String sanitise(@CheckForNull String part) {
        if (part == null) {
            return null;
        }
        String addr = part.replace("\r", "").replace("\n", "").trim();
        if (addr.isEmpty()) {
            return null;
        }
        int at = addr.indexOf('@');
        if (at <= 0 || at != addr.lastIndexOf('@') || at == addr.length() - 1) {
            return null;
        }
        if (addr.indexOf(' ') >= 0 || addr.indexOf('<') >= 0 || addr.indexOf('>') >= 0) {
            return null;
        }
        return addr;
    }
}
