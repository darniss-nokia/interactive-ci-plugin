// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.view;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.Serializable;
import net.sf.json.JSONObject;

/**
 * A single Confluence-style comment on a {@link ReviewDocument}.
 *
 * <p>A comment is either anchored to a source line ({@link #getLine()} &ge; 1) or a general,
 * document-level note ({@link #getLine()} == {@link #GENERAL}). The {@link #getBody() body} is raw
 * markdown authored by the reviewer; it is rendered to <em>sanitised</em> HTML at the REST layer
 * (never stored as HTML), so this model only ever holds the untrusted source text.
 *
 * <p>A comment may {@linkplain #getParentId() reply} to another comment (threading), and may carry a
 * display-only {@linkplain #getAuthorLabel() author label} shown instead of the real author — used by an
 * automation (e.g. the regenerate agent) to post a labelled "AI response" nested under a reviewer's
 * comment. The {@link #getAuthor() author} always remains the true, server-set Jenkins/token identity for
 * audit and is never client-supplied.
 *
 * <p>A comment left by highlighting text may also carry a display-only {@linkplain #getQuote() quote} — the
 * verbatim snippet that was highlighted — so the viewer can show back exactly what the reviewer selected,
 * even when it is a sub-phrase of a line or spans several lines. Anchoring still uses {@link #getLine()}.
 *
 * <p>Persisted via XStream as part of the owning {@link ReviewDocument}. All transitions on the
 * comment (only {@link #setResolved(boolean)}) are performed by {@code ViewStore} while it holds the
 * owning document's monitor.
 */
public class ReviewComment implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Sentinel {@link #getLine()} value for a general (not line-anchored) comment. */
    public static final int GENERAL = -1;

    @NonNull
    private final String id;

    /** 1-based source line the comment is anchored to, or {@link #GENERAL} for a document-level note. */
    private final int line;

    @NonNull
    private final String body;

    @NonNull
    private final String author;

    private final long createdTs;

    /**
     * Threading: the id of the comment this one replies to, or {@code null} for a root comment. XStream-safe:
     * absent in legacy {@code views.xml}, so it deserialises to {@code null}.
     */
    @CheckForNull
    private final String parentId;

    /**
     * Display-only label shown <em>instead of</em> the real {@link #author} (for example an automation name
     * such as "AI response"), or {@code null} to show the real author. Never affects {@link #author}, which
     * always holds the true, server-set identity kept for audit. XStream-safe: {@code null} in legacy data.
     */
    @CheckForNull
    private final String authorLabel;

    /**
     * Optional verbatim snippet of the exact text the reviewer highlighted when leaving this comment (see the
     * viewer's highlight-select flow). The comment still <em>anchors</em> at {@link #getLine()}; this field
     * only records <em>what</em> was highlighted — which may be a sub-phrase of a long line or span several
     * lines — so the viewer can show it back verbatim. It is display-only, rendered by the client via
     * {@code textContent} (never markdown/HTML), and length-bounded at the REST layer. {@code null} for the
     * "+"/general path and legacy data. XStream-safe: absent in {@code views.xml} that predates it, so it
     * deserialises to {@code null}.
     */
    @CheckForNull
    private final String quote;

    private boolean resolved;

    /** Back-compat constructor: a root comment shown under its real author (no reply, no display label). */
    public ReviewComment(@NonNull String id, int line, @NonNull String body, @NonNull String author, long createdTs) {
        this(id, line, body, author, createdTs, null, null);
    }

    /** Back-compat constructor: a comment with threading/label but no highlighted-selection quote. */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public ReviewComment(
            @NonNull String id,
            int line,
            @NonNull String body,
            @NonNull String author,
            long createdTs,
            @CheckForNull String parentId,
            @CheckForNull String authorLabel) {
        this(id, line, body, author, createdTs, parentId, authorLabel, null);
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    public ReviewComment(
            @NonNull String id,
            int line,
            @NonNull String body,
            @NonNull String author,
            long createdTs,
            @CheckForNull String parentId,
            @CheckForNull String authorLabel,
            @CheckForNull String quote) {
        this.id = id;
        this.line = line;
        this.body = body;
        this.author = author;
        this.createdTs = createdTs;
        this.parentId = parentId;
        this.authorLabel = authorLabel;
        this.quote = quote;
    }

    @NonNull
    public String getId() {
        return id;
    }

    public int getLine() {
        return line;
    }

    /** @return {@code true} if this comment is anchored to a specific source line. */
    public boolean isLineAnchored() {
        return line >= 1;
    }

    @NonNull
    public String getBody() {
        return body;
    }

    @NonNull
    public String getAuthor() {
        return author;
    }

    public long getCreatedTs() {
        return createdTs;
    }

    /** @return the id of the comment this one replies to, or {@code null} if it is a root comment. */
    @CheckForNull
    public String getParentId() {
        return parentId;
    }

    /** @return {@code true} if this comment is a reply nested under another comment. */
    public boolean isReply() {
        return parentId != null;
    }

    /**
     * @return a display-only label to show instead of the real {@link #getAuthor() author} (e.g. an
     *     automation name like "AI response"), or {@code null} to show the real author. This never changes
     *     {@link #getAuthor()}, which always holds the true, server-set identity used for audit.
     */
    @CheckForNull
    public String getAuthorLabel() {
        return authorLabel;
    }

    /**
     * @return the verbatim text the reviewer highlighted when leaving this comment (a sub-phrase or a
     *     multi-line span), or {@code null} when the comment was left via the "+" affordance / as a general
     *     note, or for legacy data. Display-only; the comment still anchors at {@link #getLine()}.
     */
    @CheckForNull
    public String getQuote() {
        return quote;
    }

    public boolean isResolved() {
        return resolved;
    }

    public void setResolved(boolean resolved) {
        this.resolved = resolved;
    }

    /**
     * @return this comment as JSON. The {@code body} is the raw markdown source; the caller (REST
     *     layer) is responsible for adding a sanitised {@code bodyHtml} rendered via
     *     {@code MarkdownRenderer} — HTML escaping is never done here.
     */
    @NonNull
    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("line", line);
        o.put("body", body);
        o.put("author", author);
        o.put("createdTs", createdTs);
        o.put("resolved", resolved);
        // Threading + display label are optional: emitted only when set, so root comments and legacy data
        // keep the same lean JSON shape and the client treats an absent parentId/authorLabel as "none".
        if (parentId != null) {
            o.put("parentId", parentId);
        }
        if (authorLabel != null) {
            o.put("authorLabel", authorLabel);
        }
        if (quote != null) {
            o.put("quote", quote);
        }
        return o;
    }
}
