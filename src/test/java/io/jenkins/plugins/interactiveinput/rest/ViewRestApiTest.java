// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Item;
import io.jenkins.plugins.interactiveinput.config.InteractiveInputGlobalConfig;
import io.jenkins.plugins.interactiveinput.view.ReviewDocument;
import io.jenkins.plugins.interactiveinput.view.ReviewStatus;
import io.jenkins.plugins.interactiveinput.view.ViewStore;
import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.util.NameValuePair;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * REST tests for the {@code /views} endpoints: the {@code Overall/Read} + {@code Item.BUILD} permission
 * matrix, 404 existence-hiding, feature-flag gating, CSRF-crumbed mutations, and the security invariant
 * that HTML/code content is returned as raw text (never server-rendered) while markdown and comment
 * bodies are sanitised.
 */
@WithJenkins
class ViewRestApiTest {

    private static final String JOB = "job-a";
    private static final String BASE = "interactive-input/api/v1/";

    @Test
    void listRequiresOverallReadAndFiltersByItemRead(JenkinsRule j) throws Exception {
        secure(j);
        seed("v1", ReviewDocument.FORMAT_TEXT, "text", true, false);

        // No Overall/Read at all -> 403 at the endpoint gate.
        assertEquals(403, get(j.createWebClient(), j, BASE + "views").getStatusCode());

        // reader holds Item.READ -> sees the notify-enabled OPEN review.
        WebResponse readerResp = get(j.createWebClient().login("reader"), j, BASE + "views");
        assertEquals(200, readerResp.getStatusCode());
        assertEquals(1, json(readerResp).getInt("count"));
    }

    @Test
    void summaryJsonCarriesStarterAndBuildDeletedFlag(JenkinsRule j) throws Exception {
        secure(j);
        seed("v1", ReviewDocument.FORMAT_TEXT, "text", true, false);

        WebResponse resp = get(j.createWebClient().login("reader"), j, BASE + "views");
        assertEquals(200, resp.getStatusCode());
        JSONArray views = json(resp).getJSONArray("views");
        assertEquals(1, views.size());
        JSONObject v = views.getJSONObject(0);
        // createdBy is set server-side from the build cause; the bell renders it as "started by <user>".
        assertEquals("tester", v.getString("createdBy"), "the review's starter is exposed for the bell label");
        assertFalse(v.getBoolean("buildDeleted"), "a live review is not build-deleted");
    }

    @Test
    void detailIsHiddenWithoutItemRead(JenkinsRule j) throws Exception {
        secure(j);
        seed("v1", ReviewDocument.FORMAT_TEXT, "text", true, false);

        WebResponse ok = get(j.createWebClient().login("builder"), j, BASE + "views/v1");
        assertEquals(200, ok.getStatusCode());
        assertEquals("v1", json(ok).getString("id"));

        // Anonymous lacks Item.READ -> 404 (never reveal existence).
        assertEquals(404, get(j.createWebClient(), j, BASE + "views/v1").getStatusCode());
        // Unknown id -> 404.
        assertEquals(
                404,
                get(j.createWebClient().login("builder"), j, BASE + "views/nope")
                        .getStatusCode());
    }

    @Test
    void featureFlagOffReturns404(JenkinsRule j) throws Exception {
        secure(j);
        seed("v1", ReviewDocument.FORMAT_TEXT, "text", true, false);
        InteractiveInputGlobalConfig cfg = InteractiveInputGlobalConfig.get();
        cfg.getFeatures().setInteractiveView(false);
        cfg.save();

        assertEquals(
                404, get(j.createWebClient().login("reader"), j, BASE + "views").getStatusCode());
        assertEquals(
                404,
                get(j.createWebClient().login("builder"), j, BASE + "views/v1").getStatusCode());
    }

    @Test
    void commentRequiresBuildPermissionAndCrumb(JenkinsRule j) throws Exception {
        secure(j);
        seed("v1", ReviewDocument.FORMAT_TEXT, "text", true, false);

        // reader has Item.READ but not Item.BUILD -> cannot contribute.
        assertEquals(
                403, postJson(j.createWebClient().login("reader"), j, BASE + "views/v1/comments", "{\"body\":\"hi\"}"));
        // builder without a crumb -> CSRF rejected (403).
        assertEquals(
                403,
                postNoCrumb(j.createWebClient().login("builder"), j, BASE + "views/v1/comments", "{\"body\":\"hi\"}"));
        // builder with a crumb -> 200.
        assertEquals(
                200,
                postJson(
                        j.createWebClient().login("builder"),
                        j,
                        BASE + "views/v1/comments",
                        "{\"body\":\"looks good\"}"));

        JSONObject detail = json(get(j.createWebClient().login("builder"), j, BASE + "views/v1"));
        assertEquals(1, detail.getJSONArray("comments").size());
    }

    @Test
    void editRespectsEditableFlagAndVersionsTheCopy(JenkinsRule j) throws Exception {
        secure(j);
        seed("ro", ReviewDocument.FORMAT_TEXT, "original", true, false); // not editable
        seed("rw", ReviewDocument.FORMAT_TEXT, "original", true, true); // editable

        assertEquals(
                409,
                postJson(j.createWebClient().login("builder"), j, BASE + "views/ro/edit", "{\"content\":\"x\"}"),
                "editing a non-editable review is a 409");

        assertEquals(
                200,
                postJson(j.createWebClient().login("builder"), j, BASE + "views/rw/edit", "{\"content\":\"revised\"}"));
        JSONObject detail = json(get(j.createWebClient().login("builder"), j, BASE + "views/rw"));
        assertEquals(2, detail.getInt("currentVersion"));
        assertEquals("revised", detail.getString("content"));

        // The original version is still retrievable (edit-copy history).
        JSONObject v1 = json(get(j.createWebClient().login("builder"), j, BASE + "views/rw/raw?version=1"));
        assertEquals("original", v1.getString("content"));
    }

    @Test
    void editWithNoteRecordsItInTheVersionHistoryElseDefaults(JenkinsRule j) throws Exception {
        secure(j);
        seed("rw2", ReviewDocument.FORMAT_TEXT, "original", true, true); // editable

        // An AI course-correction can carry a summary note that lands in the version history (v2), so the
        // viewer's version dropdown shows why the edit happened rather than a generic "edited".
        assertEquals(
                200,
                postJson(
                        j.createWebClient().login("builder"),
                        j,
                        BASE + "views/rw2/edit",
                        "{\"content\":\"revised by AI\",\"note\":\"Course-corrected: tightened wording\"}"));
        JSONArray versions = json(get(j.createWebClient().login("builder"), j, BASE + "views/rw2"))
                .getJSONArray("versions");
        assertEquals(
                "Course-corrected: tightened wording",
                versions.getJSONObject(1).getString("note"),
                "the supplied note is recorded on the new version");

        // An edit with no note keeps the default label, so legacy clients are unaffected.
        assertEquals(
                200,
                postJson(j.createWebClient().login("builder"), j, BASE + "views/rw2/edit", "{\"content\":\"again\"}"));
        JSONArray after = json(get(j.createWebClient().login("builder"), j, BASE + "views/rw2"))
                .getJSONArray("versions");
        assertEquals("edited", after.getJSONObject(2).getString("note"), "a note-less edit falls back to 'edited'");
    }

    @Test
    void decisionApprovesThenConflictsOnRepeat(JenkinsRule j) throws Exception {
        secure(j);
        seed("v1", ReviewDocument.FORMAT_TEXT, "text", true, false);

        assertEquals(
                200,
                postJson(
                        j.createWebClient().login("builder"),
                        j,
                        BASE + "views/v1/decision",
                        "{\"decision\":\"approve\"}"));
        assertEquals(ReviewStatus.APPROVED, ViewStore.get().get("v1").getStatus());

        assertEquals(
                409,
                postJson(
                        j.createWebClient().login("builder"),
                        j,
                        BASE + "views/v1/decision",
                        "{\"decision\":\"reject\"}"),
                "a second decision on a decided review is a 409");

        // An unknown decision verb is a 400.
        seed("v2", ReviewDocument.FORMAT_TEXT, "text", true, false);
        assertEquals(
                400,
                postJson(
                        j.createWebClient().login("builder"),
                        j,
                        BASE + "views/v2/decision",
                        "{\"decision\":\"maybe\"}"));
    }

    @Test
    void requestChangesSetsStatusAndReturnsCommentsForRegeneration(JenkinsRule j) throws Exception {
        secure(j);
        seed("c1", ReviewDocument.FORMAT_MARKDOWN, "# Title\n\nBody line.", true, false);

        // A reviewer adds a line-anchored comment (line 3 = "Body line.")...
        assertEquals(
                200,
                postJson(
                        j.createWebClient().login("builder"),
                        j,
                        BASE + "views/c1/comments",
                        "{\"body\":\"tighten this\",\"line\":3}"));

        // ...then clicks "Request changes". The decision resolves to CHANGES_REQUESTED and the response
        // carries the comments back so the pipeline can hand them to a generator for a regenerate loop.
        WebResponse resp = postJsonResponse(
                j.createWebClient().login("builder"),
                j,
                BASE + "views/c1/decision",
                "{\"decision\":\"request-changes\"}");
        assertEquals(200, resp.getStatusCode());
        JSONObject body = json(resp);
        assertEquals("CHANGES_REQUESTED", body.getString("status"));
        JSONArray comments = body.getJSONArray("comments");
        assertEquals(1, comments.size());
        assertEquals(3, comments.getJSONObject(0).getInt("line"));
        assertEquals(ReviewStatus.CHANGES_REQUESTED, ViewStore.get().get("c1").getStatus());
    }

    @Test
    void regenerateLoopEditsInPlaceWithNoteAfterRequestChanges(JenkinsRule j) throws Exception {
        secure(j);
        seed("rg", ReviewDocument.FORMAT_MARKDOWN, "# Title\n\nBody line.", true, true); // editable

        // A reviewer comments then clicks "Request changes" -> the review becomes CHANGES_REQUESTED.
        JSONObject afterRoot = json(postJsonResponse(
                j.createWebClient().login("builder"),
                j,
                BASE + "views/rg/comments",
                "{\"body\":\"expand this\",\"line\":3}"));
        String rootId = afterRoot.getJSONArray("comments").getJSONObject(0).getString("id");
        assertEquals(
                200,
                postJson(
                        j.createWebClient().login("builder"),
                        j,
                        BASE + "views/rg/decision",
                        "{\"decision\":\"request-changes\"}"));
        assertEquals(ReviewStatus.CHANGES_REQUESTED, ViewStore.get().get("rg").getStatus());

        // The regenerate-agent course-corrects the SAME review in place. CHANGES_REQUESTED must permit the
        // edit (it previously 409'd on any decided state), and the summary note lands in the version history.
        assertEquals(
                200,
                postJson(
                        j.createWebClient().login("builder"),
                        j,
                        BASE + "views/rg/edit",
                        "{\"content\":\"# Title\\n\\nExpanded body line.\",\"note\":\"Regenerated: expanded per review\"}"),
                "editing a CHANGES_REQUESTED review is allowed for the regenerate loop");
        JSONObject detail = json(get(j.createWebClient().login("builder"), j, BASE + "views/rg"));
        JSONArray versions = detail.getJSONArray("versions");
        assertEquals(
                "Regenerated: expanded per review",
                versions.getJSONObject(versions.size() - 1).getString("note"),
                "the AI summary note is recorded on the new version");
        assertEquals("CHANGES_REQUESTED", detail.getString("status"), "an in-place edit does not re-open the review");

        // The automation also threads a reply under the reviewer's comment (still allowed post-decision),
        // and the audit author stays the real, server-set identity.
        JSONObject afterReply = json(postJsonResponse(
                j.createWebClient().login("builder"),
                j,
                BASE + "views/rg/comments",
                "{\"body\":\"expanded the body\",\"line\":3,\"parentId\":\"" + rootId + "\",\"automated\":true}"));
        JSONObject reply = findReply(afterReply.getJSONArray("comments"));
        assertEquals(rootId, reply.getString("parentId"));
        assertEquals("builder", reply.getString("author"), "the audit author stays real for the automated reply");

        // A finally-decided (APPROVED) review remains read-only — the relaxation is scoped to the
        // "please course-correct" state only.
        seed("rgApproved", ReviewDocument.FORMAT_MARKDOWN, "# T\n\nB.", true, true);
        postJson(
                j.createWebClient().login("builder"),
                j,
                BASE + "views/rgApproved/decision",
                "{\"decision\":\"approve\"}");
        assertEquals(
                409,
                postJson(
                        j.createWebClient().login("builder"), j, BASE + "views/rgApproved/edit", "{\"content\":\"x\"}"),
                "an approved review stays read-only");
    }

    @Test
    void commentAndResolveResponsesCarryContentSoTheLeftPaneSurvives(JenkinsRule j) throws Exception {
        secure(j);
        seed("cc", ReviewDocument.FORMAT_MARKDOWN, "# Title\n\nBody line.", true, false);

        // Req 3a: POST /comments must return the FULL document (content + renderedHtml) — not a summary —
        // so the client re-renders the detail pane without blanking it (the inline-comment "crash").
        WebResponse addResp = postJsonResponse(
                j.createWebClient().login("builder"), j, BASE + "views/cc/comments", "{\"body\":\"note\",\"line\":3}");
        assertEquals(200, addResp.getStatusCode());
        JSONObject added = json(addResp);
        assertEquals("# Title\n\nBody line.", added.getString("content"), "comment response must carry content");
        assertTrue(added.has("renderedHtml"), "markdown comment response must carry renderedHtml");
        String commentId = added.getJSONArray("comments").getJSONObject(0).getString("id");

        // The resolve toggle must likewise return the full document so the pane survives a resolve.
        WebResponse resolveResp = postJsonResponse(
                j.createWebClient().login("builder"),
                j,
                BASE + "views/cc/resolveComment",
                "{\"commentId\":\"" + commentId + "\",\"resolved\":true}");
        assertEquals(200, resolveResp.getStatusCode());
        JSONObject resolved = json(resolveResp);
        assertEquals("# Title\n\nBody line.", resolved.getString("content"), "resolve response must carry content");
        assertTrue(resolved.has("renderedHtml"), "resolve response must carry renderedHtml");
        assertTrue(
                resolved.getJSONArray("comments").getJSONObject(0).getBoolean("resolved"),
                "the comment must now be marked resolved");
    }

    @Test
    void highlightCommentCarriesVerbatimQuoteWhileGeneralCommentDropsIt(JenkinsRule j) throws Exception {
        secure(j);
        seed("hq", ReviewDocument.FORMAT_MARKDOWN, "# Title\n\nA long paragraph line to highlight.", true, false);

        // A reviewer highlights only a sub-phrase of a line and comments: the server stores and returns that
        // verbatim snippet (quote) alongside the whole-line anchor, so the viewer can show back exactly what
        // was selected instead of collapsing it to the whole line.
        String snippet = "sub-phrase of a line";
        JSONObject after = json(postJsonResponse(
                j.createWebClient().login("builder"),
                j,
                BASE + "views/hq/comments",
                "{\"body\":\"why this?\",\"line\":3,\"quote\":\"" + snippet + "\"}"));
        JSONObject c = after.getJSONArray("comments").getJSONObject(0);
        assertEquals(3, c.getInt("line"), "the comment still anchors at the highlighted line");
        assertEquals(snippet, c.getString("quote"), "the verbatim highlighted snippet must be preserved");

        // A general (unanchored) comment carries no quote — a quote is only meaningful for a line anchor.
        JSONObject afterGeneral = json(postJsonResponse(
                j.createWebClient().login("builder"),
                j,
                BASE + "views/hq/comments",
                "{\"body\":\"overall note\",\"quote\":\"ignored\"}"));
        boolean sawGeneralQuote = false;
        JSONArray all = afterGeneral.getJSONArray("comments");
        for (int i = 0; i < all.size(); i++) {
            JSONObject cc = all.getJSONObject(i);
            if (cc.optInt("line", -1) < 1 && cc.has("quote")) {
                sawGeneralQuote = true;
            }
        }
        assertFalse(sawGeneralQuote, "a general comment must not carry a quote");
    }

    @Test
    void replyThreadsUnderParentWithLabelButKeepsRealAuditAuthor(JenkinsRule j) throws Exception {
        secure(j);
        seed("rp", ReviewDocument.FORMAT_MARKDOWN, "# Title\n\nBody line.", true, false);

        // A reviewer leaves a root comment on line 3.
        JSONObject afterRoot = json(postJsonResponse(
                j.createWebClient().login("builder"),
                j,
                BASE + "views/rp/comments",
                "{\"body\":\"tighten this\",\"line\":3}"));
        String rootId = afterRoot.getJSONArray("comments").getJSONObject(0).getString("id");

        // An automation replies to that comment with a display label. The audit author must stay the real,
        // server-set Jenkins identity (builder) — the client-supplied label only changes what is displayed.
        JSONObject afterReply = json(postJsonResponse(
                j.createWebClient().login("builder"),
                j,
                BASE + "views/rp/comments",
                "{\"body\":\"done\",\"line\":3,\"parentId\":\"" + rootId + "\",\"authorLabel\":\"AI response\"}"));
        JSONObject reply = findReply(afterReply.getJSONArray("comments"));
        assertEquals(rootId, reply.getString("parentId"), "the reply must be threaded under the root comment");
        assertEquals("AI response", reply.getString("authorLabel"), "the display label must be preserved");
        assertEquals("builder", reply.getString("author"), "the audit author is the real identity, never the label");
    }

    @Test
    void automatedReplyUsesConfiguredGlobalLabelAndExplicitLabelWins(JenkinsRule j) throws Exception {
        secure(j);
        seed("au", ReviewDocument.FORMAT_MARKDOWN, "# Title\n\nBody line.", true, false);
        InteractiveInputGlobalConfig cfg = InteractiveInputGlobalConfig.get();
        cfg.setAutomationReplyName("JENKINS response");
        cfg.save();

        JSONObject afterRoot = json(postJsonResponse(
                j.createWebClient().login("builder"), j, BASE + "views/au/comments", "{\"body\":\"root\",\"line\":3}"));
        String rootId = afterRoot.getJSONArray("comments").getJSONObject(0).getString("id");

        // automated:true with no explicit label -> the configured global default is applied server-side.
        JSONObject afterAuto = json(postJsonResponse(
                j.createWebClient().login("builder"),
                j,
                BASE + "views/au/comments",
                "{\"body\":\"auto\",\"line\":3,\"parentId\":\"" + rootId + "\",\"automated\":true}"));
        assertEquals(
                "JENKINS response",
                findReply(afterAuto.getJSONArray("comments")).getString("authorLabel"),
                "an automated reply with no explicit label uses the configured global default");

        // An explicit authorLabel overrides the global default even when automated:true.
        JSONObject afterOverride = json(postJsonResponse(
                j.createWebClient().login("builder"),
                j,
                BASE + "views/au/comments",
                "{\"body\":\"custom\",\"line\":3,\"parentId\":\"" + rootId
                        + "\",\"automated\":true,\"authorLabel\":\"Custom bot\"}"));
        boolean sawCustom = false;
        JSONArray comments = afterOverride.getJSONArray("comments");
        for (int i = 0; i < comments.size(); i++) {
            JSONObject c = comments.getJSONObject(i);
            if (c.has("authorLabel") && "Custom bot".equals(c.getString("authorLabel"))) {
                sawCustom = true;
            }
        }
        assertTrue(sawCustom, "an explicit authorLabel must override the global default");
    }

    @Test
    void replyToAMissingParentIsRejectedWith400(JenkinsRule j) throws Exception {
        secure(j);
        seed("bad", ReviewDocument.FORMAT_MARKDOWN, "# Title\n\nBody line.", true, false);

        // A parentId that does not reference an existing comment is a client error (400), not a 500/404.
        assertEquals(
                400,
                postJson(
                        j.createWebClient().login("builder"),
                        j,
                        BASE + "views/bad/comments",
                        "{\"body\":\"reply\",\"line\":3,\"parentId\":\"no-such-comment\"}"));
    }

    @Test
    void markdownRenderedHtmlCarriesSourceLineAnchors(JenkinsRule j) throws Exception {
        secure(j);
        seed("md", ReviewDocument.FORMAT_MARKDOWN, "# Heading\n\nA paragraph.", true, false);

        JSONObject detail = json(get(j.createWebClient().login("builder"), j, BASE + "views/md"));
        String html = detail.getString("renderedHtml");
        // Each top-level block is tagged with its 1-based source line so the client can anchor inline
        // comments on the RENDERED view too (line 1 = heading, line 3 = paragraph after the blank line).
        assertTrue(html.contains("data-source-line=\"1\""), "heading must anchor to source line 1: " + html);
        assertTrue(html.contains("data-source-line=\"3\""), "paragraph must anchor to source line 3: " + html);
    }

    @Test
    void markdownTablesRenderToHtmlTableEndToEnd(JenkinsRule j) throws Exception {
        secure(j);
        // The exact class of content that regressed in the live Interactive View: a GFM pipe table in a
        // markdown review must reach the client as an HTML <table>, not a literal "|"-delimited paragraph.
        seed(
                "tbl",
                ReviewDocument.FORMAT_MARKDOWN,
                "# Report\n\n| Aspect | Value |\n|---|---|\n| length | 1..255 |",
                true,
                false);

        JSONObject detail = json(get(j.createWebClient().login("builder"), j, BASE + "views/tbl"));
        String html = detail.getString("renderedHtml");
        // The document body uses the source-line renderer, so the table opens as <table data-source-line=..>.
        assertTrue(html.contains("<table"), "a GFM pipe table must render as an HTML table: " + html);
        assertTrue(html.contains("<th>Aspect</th>"), "table header cells must render: " + html);
        assertFalse(html.contains("|---|"), "the delimiter row must not leak through as literal text: " + html);
    }

    @Test
    void htmlAndCodeContentIsReturnedRawAndNeverServerRendered(JenkinsRule j) throws Exception {
        secure(j);
        String payload = "<script>alert(1)</script>";
        seed("h", ReviewDocument.FORMAT_HTML, payload, true, false);

        JSONObject detail = json(get(j.createWebClient().login("builder"), j, BASE + "views/h"));
        // Content is returned verbatim (the client shows it ESCAPED); the server must not pre-render it.
        assertEquals(payload, detail.getString("content"));
        assertFalse(detail.has("renderedHtml"), "HTML/code content must never be server-rendered to HTML");
    }

    @Test
    void markdownAndCommentBodiesAreSanitised(JenkinsRule j) throws Exception {
        secure(j);
        seed("m", ReviewDocument.FORMAT_MARKDOWN, "<script>alert(1)</script>\n\n[x](javascript:alert(1))", true, false);

        JSONObject detail = json(get(j.createWebClient().login("builder"), j, BASE + "views/m"));
        assertTrue(detail.has("renderedHtml"), "markdown is rendered to sanitised HTML");
        String html = detail.getString("renderedHtml");
        assertFalse(html.contains("<script>"), "raw <script> must be escaped: " + html);
        assertFalse(html.contains("javascript:"), "javascript: scheme must be sanitised: " + html);

        // Comment bodies are untrusted markdown too and must be sanitised in bodyHtml.
        assertEquals(
                200,
                postJson(
                        j.createWebClient().login("builder"),
                        j,
                        BASE + "views/m/comments",
                        "{\"body\":\"<script>bad</script>\\n\\n[l](javascript:alert(1))\"}"));
        JSONObject after = json(get(j.createWebClient().login("builder"), j, BASE + "views/m"));
        String bodyHtml = after.getJSONArray("comments").getJSONObject(0).getString("bodyHtml");
        assertFalse(bodyHtml.contains("<script>"), "comment <script> must be escaped: " + bodyHtml);
        assertFalse(bodyHtml.contains("javascript:"), "comment javascript: must be sanitised: " + bodyHtml);
    }

    // ---- Downloads (item #1): a single file, and a whole-group ZIP; Item.READ gated, 404 no-leak ----

    @Test
    void downloadReturnsFileContentAsAttachment(JenkinsRule j) throws Exception {
        secure(j);
        seedFile("d1", "notes.md", "hello world", null);

        WebResponse resp = get(j.createWebClient().login("builder"), j, BASE + "views/d1/download");
        assertEquals(200, resp.getStatusCode());
        String cd = resp.getResponseHeaderValue("Content-Disposition");
        assertTrue(cd != null && cd.contains("attachment"), "must be an attachment: " + cd);
        assertTrue(cd.contains("notes.md"), "the file basename must be the download name: " + cd);
        assertEquals("nosniff", resp.getResponseHeaderValue("X-Content-Type-Options"), "must forbid MIME sniffing");
        assertEquals("hello world", resp.getContentAsString(), "the body is the current version's content");
    }

    @Test
    void downloadIsGatedByItemReadAndHidesExistence(JenkinsRule j) throws Exception {
        secure(j);
        seedFile("d1", "notes.md", "hello", null);

        // Item.READ (reader) is sufficient — a download is a read, not a contribution (Item.BUILD).
        assertEquals(
                200,
                get(j.createWebClient().login("reader"), j, BASE + "views/d1/download")
                        .getStatusCode());
        // Anonymous lacks Item.READ -> 404 (never reveal existence); an unknown id is also 404.
        assertEquals(
                404, get(j.createWebClient(), j, BASE + "views/d1/download").getStatusCode());
        assertEquals(
                404,
                get(j.createWebClient().login("builder"), j, BASE + "views/nope/download")
                        .getStatusCode());
    }

    @Test
    void downloadGroupZipsEveryReadableGroupMember(JenkinsRule j) throws Exception {
        secure(j);
        // Two files published together share a groupId; a third, unrelated file must NOT be included.
        seedFile("g1a", "a.md", "AAA", "grp");
        seedFile("g1b", "b.md", "BBB", "grp");
        seedFile("other", "c.md", "CCC", null);

        WebResponse resp = get(j.createWebClient().login("builder"), j, BASE + "views/g1a/downloadGroup");
        assertEquals(200, resp.getStatusCode());
        assertTrue(
                resp.getContentType().contains("zip"), "a group download is a zip archive: " + resp.getContentType());
        String cd = resp.getResponseHeaderValue("Content-Disposition");
        assertTrue(cd != null && cd.contains("attachment") && cd.contains(".zip"), "zip attachment: " + cd);

        Map<String, String> entries = unzip(resp);
        assertEquals(2, entries.size(), "only the two group members are archived: " + entries.keySet());
        assertEquals("AAA", entries.get("a.md"));
        assertEquals("BBB", entries.get("b.md"));
        assertFalse(entries.containsKey("c.md"), "an unrelated file must not leak into the group zip");
    }

    @Test
    void downloadGroupForALoneFileIsASingleEntryZip(JenkinsRule j) throws Exception {
        secure(j);
        seedFile("solo", "solo.md", "SOLO", null); // ungrouped -> getGroupId() falls back to its own id

        WebResponse resp = get(j.createWebClient().login("builder"), j, BASE + "views/solo/downloadGroup");
        assertEquals(200, resp.getStatusCode());
        Map<String, String> entries = unzip(resp);
        assertEquals(1, entries.size(), "a lone file yields a one-entry archive: " + entries.keySet());
        assertEquals("SOLO", entries.get("solo.md"));
    }

    @Test
    void downloadGroupIsGatedByItemReadAndHidesExistence(JenkinsRule j) throws Exception {
        secure(j);
        seedFile("g1a", "a.md", "AAA", "grp");

        assertEquals(
                404,
                get(j.createWebClient(), j, BASE + "views/g1a/downloadGroup").getStatusCode());
        assertEquals(
                404,
                get(j.createWebClient().login("builder"), j, BASE + "views/nope/downloadGroup")
                        .getStatusCode());
    }

    // ---- rendered HTML view: served only for HTML, only when enabled, always sandboxed ----

    /** A self-contained report: all of its content is produced by script, as a Robot log.html is. */
    private static final String SCRIPTED_REPORT =
            "<!DOCTYPE html><html><head><style>body{color:red}</style></head>"
                    + "<body><div id=\"c\"></div><script>document.getElementById('c').textContent='hi'</script>"
                    + "</body></html>";

    @Test
    void renderedServesHtmlVerbatimUnderASandboxCsp(JenkinsRule j) throws Exception {
        secure(j);
        seedFormatted("h1", "log.html", ReviewDocument.FORMAT_HTML, SCRIPTED_REPORT);

        WebResponse resp = get(j.createWebClient().login("builder"), j, BASE + "views/h1/rendered");
        assertEquals(200, resp.getStatusCode());
        assertTrue(resp.getContentType().contains("text/html"), "must render as HTML: " + resp.getContentType());
        // A browser enforces EVERY CSP header it receives, so core's page policy (script-src 'self') must
        // have been REPLACED rather than appended — otherwise the report's inline scripts are blocked and
        // the rendered view is blank. Assert there is exactly one, and that it is ours.
        List<String> csps = resp.getResponseHeaders().stream()
                .filter(h -> "Content-Security-Policy".equalsIgnoreCase(h.getName()))
                .map(NameValuePair::getValue)
                .collect(Collectors.toList());
        assertEquals(1, csps.size(), () -> "exactly one CSP header must survive, got: " + csps);
        String csp = csps.get(0);
        assertTrue(csp.contains("sandbox"), "must carry a sandbox CSP: " + csp);
        assertTrue(csp.contains("allow-scripts"), "a scripted report needs allow-scripts: " + csp);
        assertFalse(
                csp.contains("allow-same-origin"),
                "allow-same-origin would let the document reach this Jenkins session: " + csp);
        assertFalse(csp.contains("script-src"), "a script-src restriction would blank a generated report: " + csp);
        assertEquals("nosniff", resp.getResponseHeaderValue("X-Content-Type-Options"));
        // Served verbatim — sanitising the scripts away is exactly what left a generated report blank.
        assertEquals(SCRIPTED_REPORT, resp.getContentAsString(), "the snapshot is served unmodified");
    }

    @Test
    void renderedIsGatedByItemReadAndHidesExistence(JenkinsRule j) throws Exception {
        secure(j);
        seedFormatted("h1", "log.html", ReviewDocument.FORMAT_HTML, SCRIPTED_REPORT);

        // Item.READ is sufficient — rendering is a read, not a contribution.
        assertEquals(
                200,
                get(j.createWebClient().login("reader"), j, BASE + "views/h1/rendered")
                        .getStatusCode());
        assertEquals(
                404, get(j.createWebClient(), j, BASE + "views/h1/rendered").getStatusCode());
        assertEquals(
                404,
                get(j.createWebClient().login("builder"), j, BASE + "views/nope/rendered")
                        .getStatusCode());
    }

    @Test
    void renderedRefusesANonHtmlDocument(JenkinsRule j) throws Exception {
        secure(j);
        // Guard against the endpoint becoming a general "serve anything as text/html" hole: a markdown
        // document has its own safe render path and must never be served through here.
        seedFormatted("m1", "notes.md", ReviewDocument.FORMAT_MARKDOWN, "# Title\n\n<script>alert(1)</script>");

        assertEquals(
                404,
                get(j.createWebClient().login("builder"), j, BASE + "views/m1/rendered")
                        .getStatusCode());
        assertFalse(
                json(get(j.createWebClient().login("builder"), j, BASE + "views/m1"))
                        .getBoolean("htmlRenderable"),
                "a markdown document must not advertise the sandboxed HTML view");
    }

    @Test
    void renderedIsUnavailableWhenHtmlRenderingIsOff(JenkinsRule j) throws Exception {
        secure(j);
        seedFormatted("h1", "log.html", ReviewDocument.FORMAT_HTML, SCRIPTED_REPORT);
        InteractiveInputGlobalConfig.get().getFeatures().setHtmlRendering(false);

        assertEquals(
                404,
                get(j.createWebClient().login("builder"), j, BASE + "views/h1/rendered")
                        .getStatusCode(),
                "with the feature off an HTML snapshot stays source-only");
        assertFalse(
                json(get(j.createWebClient().login("builder"), j, BASE + "views/h1"))
                        .getBoolean("htmlRenderable"),
                "the client must not offer a Rendered view the server will refuse");
    }

    @Test
    void detailAdvertisesTheRenderedViewForAnHtmlSnapshot(JenkinsRule j) throws Exception {
        secure(j);
        seedFormatted("h1", "log.html", ReviewDocument.FORMAT_HTML, SCRIPTED_REPORT);

        JSONObject body = json(get(j.createWebClient().login("builder"), j, BASE + "views/h1"));
        assertTrue(body.getBoolean("htmlRenderable"), "an HTML snapshot offers the sandboxed Rendered view");
        // The content still travels as raw text for the Source view, and is never pre-rendered into the page.
        assertEquals(SCRIPTED_REPORT, body.getString("content"));
        assertFalse(body.has("renderedHtml"), "HTML must never be inlined as renderedHtml");
    }

    // ---- helpers ----

    private static void secure(JenkinsRule j) throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        MockAuthorizationStrategy auth = new MockAuthorizationStrategy();
        auth.grant(Jenkins.READ).everywhere().to("reader", "builder", "admin");
        auth.grant(Item.READ).everywhere().to("reader", "builder");
        auth.grant(Item.BUILD).everywhere().to("builder");
        auth.grant(Jenkins.ADMINISTER).everywhere().to("admin");
        j.jenkins.setAuthorizationStrategy(auth);
        j.createFreeStyleProject(JOB);
    }

    private static ReviewDocument seed(
            String id, String format, String content, boolean commentable, boolean editable) {
        ReviewDocument doc = new ReviewDocument(
                id,
                JOB,
                1,
                "Report",
                "Title",
                "file",
                format,
                "text",
                "tester",
                System.currentTimeMillis(),
                commentable,
                editable,
                true,
                false,
                null,
                0L,
                null,
                null);
        return ViewStore.get().submit(doc, content);
    }

    /** Seed a document with a specific file name and group id (for the download tests). */
    private static ReviewDocument seedFile(String id, String fileName, String content, String groupId) {
        ReviewDocument doc = new ReviewDocument(
                id,
                JOB,
                1,
                "Report",
                "Title",
                fileName,
                ReviewDocument.FORMAT_TEXT,
                "text",
                "tester",
                System.currentTimeMillis(),
                true,
                false,
                true,
                false,
                null,
                0L,
                groupId,
                null);
        return ViewStore.get().submit(doc, content);
    }

    /** Seed a document with an explicit file name AND format (for the rendered-HTML tests). */
    private static ReviewDocument seedFormatted(String id, String fileName, String format, String content) {
        ReviewDocument doc = new ReviewDocument(
                id,
                JOB,
                1,
                "Report",
                "Title",
                fileName,
                format,
                "markup",
                "tester",
                System.currentTimeMillis(),
                true,
                false,
                true,
                false,
                null,
                0L,
                null,
                null);
        return ViewStore.get().submit(doc, content);
    }

    /** Read a downloaded ZIP body into an ordered {@code entryName -> UTF-8 content} map. */
    private static Map<String, String> unzip(WebResponse resp) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(resp.getContentAsStream(), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = zis.read(buf)) != -1) {
                    bos.write(buf, 0, n);
                }
                out.put(e.getName(), bos.toString(StandardCharsets.UTF_8));
                zis.closeEntry();
            }
        }
        return out;
    }

    private static WebResponse get(JenkinsRule.WebClient wc, JenkinsRule j, String path) throws Exception {
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        wc.getOptions().setRedirectEnabled(true);
        return wc.getPage(new WebRequest(new URL(j.getURL(), path), HttpMethod.GET))
                .getWebResponse();
    }

    private static int postJson(JenkinsRule.WebClient wc, JenkinsRule j, String path, String body) throws Exception {
        return postJsonResponse(wc, j, path, body).getStatusCode();
    }

    private static WebResponse postJsonResponse(JenkinsRule.WebClient wc, JenkinsRule j, String path, String body)
            throws Exception {
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        wc.getOptions().setRedirectEnabled(false);
        WebRequest crumbReq = new WebRequest(new URL(j.getURL(), "crumbIssuer/api/json"), HttpMethod.GET);
        JSONObject crumb =
                JSONObject.fromObject(wc.getPage(crumbReq).getWebResponse().getContentAsString());
        WebRequest req = new WebRequest(new URL(j.getURL(), path), HttpMethod.POST);
        req.setAdditionalHeader(crumb.getString("crumbRequestField"), crumb.getString("crumb"));
        req.setAdditionalHeader("Content-Type", "application/json");
        req.setRequestBody(body);
        return wc.getPage(req).getWebResponse();
    }

    private static int postNoCrumb(JenkinsRule.WebClient wc, JenkinsRule j, String path, String body) throws Exception {
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        wc.getOptions().setRedirectEnabled(false);
        WebRequest req = new WebRequest(new URL(j.getURL(), path), HttpMethod.POST);
        req.setAdditionalHeader("Content-Type", "application/json");
        req.setRequestBody(body);
        return wc.getPage(req).getWebResponse().getStatusCode();
    }

    private static JSONObject json(WebResponse r) {
        return JSONObject.fromObject(r.getContentAsString());
    }

    /** Return the first comment in the array that is a threaded reply (carries a {@code parentId}). */
    private static JSONObject findReply(JSONArray comments) {
        for (int i = 0; i < comments.size(); i++) {
            JSONObject c = comments.getJSONObject(i);
            if (c.has("parentId")) {
                return c;
            }
        }
        throw new AssertionError("no threaded reply found in comments: " + comments);
    }
}
