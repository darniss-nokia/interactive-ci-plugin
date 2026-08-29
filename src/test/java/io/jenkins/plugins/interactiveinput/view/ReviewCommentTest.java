// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.util.XStream2;
import net.sf.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * Model tests for {@link ReviewComment}: the threading ({@code parentId}), display-label
 * ({@code authorLabel}) and highlighted-selection ({@code quote}) fields must default to {@code null}, must
 * not disturb the real audit author, and must load back-compatibly from legacy {@code views.xml} that
 * predates them (§ XStream-safe defaults).
 */
class ReviewCommentTest {

    @Test
    void rootCommentHasNoThreadingOrLabelAndOmitsThemFromJson() {
        ReviewComment c = new ReviewComment("c1", 5, "hello", "alice", 1000L);
        assertNull(c.getParentId(), "a root comment has no parent");
        assertNull(c.getAuthorLabel(), "a root comment has no display label");
        assertFalse(c.isReply(), "a root comment is not a reply");

        JSONObject json = c.toJson();
        assertEquals("alice", json.getString("author"));
        assertFalse(json.has("parentId"), "root comment JSON omits parentId: " + json);
        assertFalse(json.has("authorLabel"), "root comment JSON omits authorLabel: " + json);
    }

    @Test
    void replyCarriesThreadingAndLabelButKeepsRealAuditAuthor() {
        ReviewComment reply = new ReviewComment("c2", 5, "done", "svc-jenkins", 2000L, "c1", "AI response");
        assertEquals("c1", reply.getParentId());
        assertTrue(reply.isReply());
        assertEquals("AI response", reply.getAuthorLabel());
        // The display label must NOT replace the real, server-set identity used for audit.
        assertEquals("svc-jenkins", reply.getAuthor(), "the audit author stays the real Jenkins identity");

        JSONObject json = reply.toJson();
        assertEquals("c1", json.getString("parentId"));
        assertEquals("AI response", json.getString("authorLabel"));
        assertEquals("svc-jenkins", json.getString("author"));
    }

    @Test
    void xstreamRoundTripPreservesThreadingAndLabel() {
        XStream2 xs = newXStream();
        ReviewComment reply = new ReviewComment("c2", 5, "done", "svc-jenkins", 2000L, "c1", "AI response");
        String xml = xs.toXML(reply);
        assertTrue(xml.contains("<parentId>c1</parentId>"), "reply xml records the parent: " + xml);
        assertTrue(xml.contains("<authorLabel>AI response</authorLabel>"), "reply xml records the label: " + xml);

        ReviewComment loaded = (ReviewComment) xs.fromXML(xml);
        assertEquals("c1", loaded.getParentId());
        assertEquals("AI response", loaded.getAuthorLabel());
        assertEquals("svc-jenkins", loaded.getAuthor());
    }

    @Test
    void highlightQuoteRoundTripsThroughJsonAndXstreamButIsOmittedWhenAbsent() {
        // A comment left via the "+" affordance has no highlighted quote: getQuote() is null and JSON omits it.
        ReviewComment plain = new ReviewComment("c1", 5, "hello", "alice", 1000L);
        assertNull(plain.getQuote(), "a non-highlight comment has no quote");
        assertFalse(plain.toJson().has("quote"), "a comment without a quote omits it from JSON: " + plain.toJson());

        // A comment left by highlighting text carries the verbatim snippet (a sub-phrase of a long line here).
        String snippet = "Use Case 2 (Processing Client Certificate) documents updated TLS session matching";
        ReviewComment quoted = new ReviewComment("c2", 5, "why 1..255?", "builder", 2000L, null, null, snippet);
        assertEquals(snippet, quoted.getQuote());
        assertEquals(snippet, quoted.toJson().getString("quote"));
        // The quote never disturbs the real audit author or the anchor line.
        assertEquals("builder", quoted.getAuthor());
        assertEquals(5, quoted.getLine());

        XStream2 xs = newXStream();
        ReviewComment loaded = (ReviewComment) xs.fromXML(xs.toXML(quoted));
        assertEquals(snippet, loaded.getQuote(), "the highlighted quote survives an XStream round-trip");
    }

    @Test
    void legacyXmlWithoutQuoteLoadsWithNullQuote() {
        // A comment persisted before the highlight quote existed: no <quote> element -> null (back-compat).
        String legacy = "<io.jenkins.plugins.interactiveinput.view.ReviewComment>"
                + "<id>c1</id><line>5</line><body>hello</body><author>alice</author>"
                + "<createdTs>1000</createdTs><resolved>false</resolved>"
                + "</io.jenkins.plugins.interactiveinput.view.ReviewComment>";
        ReviewComment c = (ReviewComment) newXStream().fromXML(legacy);
        assertNull(c.getQuote(), "legacy comment has no quote");
    }

    @Test
    void legacyXmlWithoutThreadingFieldsLoadsWithNullDefaults() {
        // A comment persisted before threading existed: no <parentId>/<authorLabel> elements. It must
        // deserialise with null defaults (backward compatibility) rather than failing to load.
        String legacy = "<io.jenkins.plugins.interactiveinput.view.ReviewComment>"
                + "<id>c1</id><line>5</line><body>hello</body><author>alice</author>"
                + "<createdTs>1000</createdTs><resolved>false</resolved>"
                + "</io.jenkins.plugins.interactiveinput.view.ReviewComment>";
        ReviewComment c = (ReviewComment) newXStream().fromXML(legacy);
        assertEquals("c1", c.getId());
        assertEquals(5, c.getLine());
        assertEquals("alice", c.getAuthor());
        assertNull(c.getParentId(), "legacy comment has no parentId");
        assertNull(c.getAuthorLabel(), "legacy comment has no authorLabel");
    }

    private static XStream2 newXStream() {
        XStream2 xs = new XStream2();
        xs.allowTypesByWildcard(new String[] {"io.jenkins.plugins.interactiveinput.**"});
        return xs;
    }
}
