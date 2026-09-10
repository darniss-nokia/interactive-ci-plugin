// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.util;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.commonmark.Extension;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TableBody;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableHead;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Document;
import org.commonmark.node.Node;
import org.commonmark.node.SourceSpan;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.AttributeProvider;
import org.commonmark.renderer.html.HtmlRenderer;

/**
 * Safe markdown-to-HTML rendering for the modal's context panel and free-text preview (§7.5).
 *
 * <p>Uses {@code commonmark-java} configured to <em>escape</em> raw HTML and sanitise URLs, so
 * user-supplied markdown ({@code contextMarkdown} and free text) can never inject script or raw HTML
 * into the DOM. This replaces the spec's suggested (and nonexistent) {@code StrictEscapesExtension}
 * with the library's supported {@code escapeHtml}/{@code sanitizeUrls} switches — see SESSION_NOTES.
 *
 * <p>GitHub-Flavored Markdown extensions (tables, strikethrough, autolinks) are enabled on top of the
 * CommonMark core — those constructs are <em>not</em> in the core spec, so without the extensions a
 * pipe table renders as a literal "|"-delimited paragraph (the Interactive View table bug). See
 * {@link #EXTENSIONS}. The extensions ship with the {@code markdown-formatter} plugin dependency, so
 * no additional jar is bundled, and escaping / URL sanitisation still apply to their output.
 */
public final class MarkdownRenderer {

    /**
     * GitHub-Flavored Markdown extensions enabled for every render (both variants below). Each must be
     * registered on the {@link Parser} <em>and</em> the {@link HtmlRenderer} to take effect:
     *
     * <ul>
     *   <li>{@link TablesExtension} — GFM pipe tables ({@code | a | b |}). Without it a table is parsed
     *       as one plain paragraph of literal "|"-delimited text — the reported Interactive View bug.</li>
     *   <li>{@link StrikethroughExtension} — {@code ~~struck~~} &rarr; {@code <del>}.</li>
     *   <li>{@link AutolinkExtension} — bare URLs / e-mail addresses become links (still routed through
     *       {@code sanitizeUrls}, and it only recognises http/https/mailto/www — never {@code javascript:}).</li>
     * </ul>
     *
     * <p>These come from the {@code markdown-formatter} plugin dependency (commonmark 0.30.0, compile
     * scope) — no extra artifact is bundled into this HPI. Task-list items are intentionally not enabled:
     * that extension is not shipped by {@code markdown-formatter}, and bundling a standalone jar would
     * violate the packaging convention (and {@code hpi.strictBundledArtifacts}).
     */
    private static final List<Extension> EXTENSIONS =
            List.of(TablesExtension.create(), StrikethroughExtension.create(), AutolinkExtension.create());

    private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();

    private static final HtmlRenderer RENDERER = HtmlRenderer.builder()
            .extensions(EXTENSIONS)
            .escapeHtml(true) // literal <script> etc. are escaped, not passed through
            .sanitizeUrls(true) // strips javascript: and other unsafe URL schemes
            .percentEncodeUrls(true)
            .build();

    // Variant that records block source positions so the Interactive View editor can anchor inline
    // (per-line) comments to rendered markdown, mapping each rendered block back to its source line.
    private static final Parser PARSER_WITH_SPANS = Parser.builder()
            .extensions(EXTENSIONS)
            .includeSourceSpans(IncludeSourceSpans.BLOCKS)
            .build();

    private static final HtmlRenderer RENDERER_WITH_SPANS = HtmlRenderer.builder()
            .extensions(EXTENSIONS)
            .escapeHtml(true)
            .sanitizeUrls(true)
            .percentEncodeUrls(true)
            .attributeProviderFactory(context -> new SourceLineAttributeProvider())
            .build();

    private MarkdownRenderer() {}

    /**
     * @param markdown raw markdown (may be {@code null})
     * @return sanitised HTML safe to insert into the DOM; empty string for {@code null}/blank input
     */
    @NonNull
    public static String render(@CheckForNull String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return "";
        }
        return RENDERER.render(PARSER.parse(normalizeGfmTables(unfenceGfmTables(markdown))));
    }

    /**
     * Same safe rendering as {@link #render(String)}, but additionally tags each top-level block element
     * with {@code data-source-line="<n>"} (1-based line in the markdown source).
     *
     * <p>Used only for the Interactive View document body so a reviewer can attach inline comments to a
     * rendered block and have them map to the same source line as the Source view (and vice-versa).
     * Escaping and URL sanitisation are identical to {@link #render(String)} — the attribute provider
     * only adds a numeric line hint, never markup or user text.
     *
     * @param markdown raw markdown (may be {@code null})
     * @return sanitised HTML with source-line anchors; empty string for {@code null}/blank input
     */
    @NonNull
    public static String renderWithSourceLines(@CheckForNull String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return "";
        }
        return RENDERER_WITH_SPANS.render(PARSER_WITH_SPANS.parse(normalizeGfmTables(unfenceGfmTables(markdown))));
    }

    // ---- GFM table delimiter repair (Interactive View "table not rendered" fix) ----

    /**
     * Repairs GFM pipe tables whose delimiter row has a different number of cells than the header row.
     *
     * <p>GitHub-Flavored Markdown (and commonmark's {@link TablesExtension}) only recognises a pipe table
     * when the header row and the delimiter row have the <em>same</em> number of columns; otherwise the
     * whole block degrades to a literal "|"-delimited paragraph. Generators occasionally emit a delimiter
     * that is off by one (for example a 21-column header with only 20 {@code ---} cells) — the reported
     * Interactive View table bug.
     *
     * <p>The pass is deliberately conservative and safe:
     *
     * <ul>
     *   <li>It only rewrites a line that is <em>already</em> a delimiter row (a pipe-containing line of
     *       only {@code -}, {@code :}, {@code |} and spaces, with at least one {@code -}) that immediately
     *       follows a pipe-containing header line — so it never invents a table from prose. A setext
     *       heading underline or a thematic break ({@code ---}) has no pipe and is therefore skipped.</li>
     *   <li>It skips lines inside fenced code blocks, so a table drawn inside a {@code ```} code sample is
     *       left verbatim.</li>
     *   <li>It only ever adjusts the delimiter line in place (padding with {@code ---} or truncating,
     *       preserving {@code :} alignment markers), so the total line count is unchanged and the
     *       source-line anchoring used by {@link #renderWithSourceLines(String)} stays correct.</li>
     * </ul>
     *
     * <p>No HTML is produced here — the result is still parsed and escaped by commonmark, so escaping and
     * URL sanitisation are entirely unaffected.
     *
     * @param markdown raw markdown (non-null)
     * @return the markdown with any mismatched table delimiter rows repaired to the header column count
     */
    @NonNull
    static String normalizeGfmTables(@NonNull String markdown) {
        if (markdown.indexOf('|') < 0) {
            return markdown; // no pipe anywhere -> no pipe tables to repair (fast path)
        }
        String[] lines = markdown.split("\n", -1);
        boolean changed = false;
        boolean inFence = false;
        char fenceChar = 0;
        int fenceLen = 0;
        int i = 0;
        while (i < lines.length) {
            String stripped = lines[i].strip();
            int run = fenceRun(stripped);
            if (inFence) {
                if (run >= fenceLen
                        && stripped.charAt(0) == fenceChar
                        && stripped.substring(run).isBlank()) {
                    inFence = false;
                }
                i++;
                continue;
            }
            if (run >= 3) {
                inFence = true;
                fenceChar = stripped.charAt(0);
                fenceLen = run;
                i++;
                continue;
            }
            // A repairable table = a pipe header line (not itself a delimiter) followed by a
            // pipe-containing delimiter row whose cell count differs. Both must be un-indented (<=3
            // columns) so a >=4-space indented code block is never treated as a table.
            if (i + 1 < lines.length
                    && leadingCols(lines[i]) <= 3
                    && lines[i].indexOf('|') >= 0
                    && !isDelimiterRow(stripped)) {
                String nextStripped = lines[i + 1].strip();
                if (leadingCols(lines[i + 1]) <= 3 && lines[i + 1].indexOf('|') >= 0 && isDelimiterRow(nextStripped)) {
                    int headerCols = splitTableCells(stripped).size();
                    List<String> delimCells = splitTableCells(nextStripped);
                    // commonmark only recognises a table when the delimiter has AT LEAST as many columns
                    // as the header (TableBlockParser.Factory: columns.size() >= headerCells.size()). A
                    // wider delimiter already renders, so only a delimiter with FEWER cells is the bug we
                    // repair — pad it up to the header column count.
                    if (headerCols >= 1 && delimCells.size() < headerCols) {
                        lines[i + 1] = rebuildDelimiterRow(delimCells, headerCols);
                        changed = true;
                        i += 2; // skip past the header and the delimiter we just repaired
                        continue;
                    }
                }
            }
            i++;
        }
        return changed ? String.join("\n", lines) : markdown;
    }

    // ---- GFM table un-fencing (render a pipe table a generator wrapped in a bare ``` fence) ----

    /**
     * Unwraps a fenced code block whose info string is empty and whose body is <em>exactly</em> a GFM pipe
     * table, so the table renders as a real {@code <table>} instead of literal "|"-delimited code.
     *
     * <p>Some report generators wrap a table in a bare ```` ``` ```` (or {@code ~~~}) fence; commonmark then
     * (correctly per the spec) renders it verbatim as {@code <pre><code>}, which the reviewer sees as raw
     * pipe text — the reported Interactive View "table inside a bullet is not rendered" case. This pass is
     * deliberately conservative:
     *
     * <ul>
     *   <li>Only a fence with <em>no</em> info string is considered — {@code ```python} (or any language)
     *       is always left as code, so intentional code samples are untouched.</li>
     *   <li>The fenced body must be a table and nothing else: a pipe-containing header line, a delimiter row
     *       directly under it ({@link #isDelimiterRow(String)}), and every other non-blank body line
     *       containing a {@code |}. A fence holding prose or non-table code is left verbatim.</li>
     *   <li>It only <em>blanks</em> the opening and closing fence lines (it never adds or removes lines), so
     *       the total line count — and therefore the {@code data-source-line} anchors used by
     *       {@link #renderWithSourceLines(String)} — is unchanged.</li>
     * </ul>
     *
     * <p>Runs before {@link #normalizeGfmTables(String)}, which then repairs any delimiter-width mismatch in
     * the now-unwrapped table. No HTML is produced here — the table is still parsed and escaped by
     * commonmark, so escaping / URL sanitisation are entirely unaffected.
     *
     * @param markdown raw markdown (non-null)
     * @return the markdown with any bare-fenced pipe tables unwrapped so they render as tables
     */
    @NonNull
    static String unfenceGfmTables(@NonNull String markdown) {
        if (markdown.indexOf('|') < 0) {
            return markdown; // no pipe anywhere -> no table to unwrap (fast path)
        }
        String[] lines = markdown.split("\n", -1);
        boolean changed = false;
        int i = 0;
        while (i < lines.length) {
            String stripped = lines[i].strip();
            int run = fenceRun(stripped);
            if (run < 3) {
                i++;
                continue;
            }
            char fenceChar = stripped.charAt(0);
            boolean hasInfoString = !stripped.substring(run).isBlank();
            int close = findFenceClose(lines, i + 1, fenceChar, run);
            if (close < 0) {
                break; // unterminated fence: leave the remainder verbatim (never misfire)
            }
            if (!hasInfoString && isFencedTableBody(lines, i + 1, close)) {
                lines[i] = "";
                lines[close] = "";
                changed = true;
            }
            i = close + 1; // never scan inside a fenced block
        }
        return changed ? String.join("\n", lines) : markdown;
    }

    /**
     * @return the index of the first line in {@code [from, len)} that closes a fence opened with
     *     {@code openRun} {@code fenceChar}s (a run &ge; the opener, of the same char, with nothing else on
     *     the line), or {@code -1} if the fence is never closed.
     */
    private static int findFenceClose(@NonNull String[] lines, int from, char fenceChar, int openRun) {
        for (int j = from; j < lines.length; j++) {
            String s = lines[j].strip();
            int r = fenceRun(s);
            if (r >= openRun && s.charAt(0) == fenceChar && s.substring(r).isBlank()) {
                return j;
            }
        }
        return -1;
    }

    /**
     * @return {@code true} if the body lines {@code [from, toExclusive)} are exactly a GFM pipe table: a
     *     pipe-containing header line, a delimiter row directly under it, and every other non-blank line
     *     containing a {@code |}. Blank lines around the table are tolerated; a single non-pipe line makes it
     *     not a pure table (left as code).
     */
    private static boolean isFencedTableBody(@NonNull String[] lines, int from, int toExclusive) {
        List<Integer> nonBlank = new ArrayList<>();
        for (int k = from; k < toExclusive; k++) {
            if (!lines[k].strip().isEmpty()) {
                nonBlank.add(k);
            }
        }
        if (nonBlank.size() < 2) {
            return false; // need at least a header and a delimiter row
        }
        String header = lines[nonBlank.get(0)].strip();
        String delim = lines[nonBlank.get(1)].strip();
        if (header.indexOf('|') < 0 || delim.indexOf('|') < 0 || !isDelimiterRow(delim)) {
            return false;
        }
        for (int k : nonBlank) {
            if (lines[k].indexOf('|') < 0) {
                return false; // a non-pipe line means this fence is not purely a table
            }
        }
        return true;
    }

    /** @return the length of a leading run of {@code `} or {@code ~} fence characters (&ge;3), else 0. */
    private static int fenceRun(@NonNull String stripped) {
        if (stripped.length() < 3) {
            return 0;
        }
        char c = stripped.charAt(0);
        if (c != '`' && c != '~') {
            return 0;
        }
        int n = 1;
        while (n < stripped.length() && stripped.charAt(n) == c) {
            n++;
        }
        return n >= 3 ? n : 0;
    }

    /** @return leading indentation in columns (tab = 4), used to skip &ge;4-space indented code. */
    private static int leadingCols(@NonNull String line) {
        int col = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == ' ') {
                col++;
            } else if (c == '\t') {
                col += 4;
            } else {
                break;
            }
        }
        return col;
    }

    /**
     * @return {@code true} if the stripped line is a table delimiter row: only {@code -}, {@code :},
     *     {@code |} and spaces, with at least one {@code -}. (Pipe presence is checked separately by the
     *     caller so a bare {@code ---} setext underline / thematic break is never treated as a delimiter.)
     */
    private static boolean isDelimiterRow(@NonNull String stripped) {
        if (stripped.isEmpty()) {
            return false;
        }
        boolean dash = false;
        for (int i = 0; i < stripped.length(); i++) {
            char c = stripped.charAt(i);
            if (c == '-') {
                dash = true;
            } else if (c != ':' && c != '|' && c != ' ' && c != '\t') {
                return false;
            }
        }
        return dash;
    }

    /**
     * Splits a table row into its cells, honouring optional leading/trailing pipes and backslash-escaped
     * pipes ({@code \|}). Used only to count/align columns during {@link #normalizeGfmTables(String)}.
     */
    @NonNull
    private static List<String> splitTableCells(@NonNull String rowStripped) {
        String s = rowStripped;
        if (s.startsWith("|")) {
            s = s.substring(1);
        }
        if (endsWithUnescapedPipe(s)) {
            s = s.substring(0, s.length() - 1);
        }
        List<String> cells = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean esc = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (esc) {
                cur.append(c);
                esc = false;
            } else if (c == '\\') {
                cur.append(c);
                esc = true;
            } else if (c == '|') {
                cells.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        cells.add(cur.toString());
        return cells;
    }

    private static boolean endsWithUnescapedPipe(@NonNull String s) {
        if (!s.endsWith("|")) {
            return false;
        }
        int bs = 0;
        int i = s.length() - 2;
        while (i >= 0 && s.charAt(i) == '\\') {
            bs++;
            i--;
        }
        return bs % 2 == 0;
    }

    /**
     * Rebuilds a delimiter row to exactly {@code headerCols} cells, preserving each existing cell's
     * {@code :} alignment markers (normalised to {@code ---}) and padding any missing columns with a
     * plain {@code ---}.
     */
    @NonNull
    private static String rebuildDelimiterRow(@NonNull List<String> delimCells, int headerCols) {
        StringBuilder sb = new StringBuilder("|");
        for (int c = 0; c < headerCols; c++) {
            String cell = c < delimCells.size() ? delimCells.get(c).strip() : "";
            boolean left = cell.startsWith(":");
            boolean right = cell.endsWith(":");
            if (cell.indexOf('-') < 0) {
                left = false; // a dashless cell (e.g. an empty padded column) becomes a plain '---'
                right = false;
            }
            sb.append(left ? ":" : "").append("---").append(right ? ":" : "").append('|');
        }
        return sb.toString();
    }

    /**
     * Adds {@code data-source-line="<n>"} (1-based source line) to every block a reviewer can comment on,
     * so the Interactive View can anchor an inline comment directly to the exact line the reviewer clicks
     * on the rendered document — with no manual line-number picker.
     *
     * <p>commonmark records a source span on every block when {@link IncludeSourceSpans#BLOCKS} is enabled,
     * and the GFM tables extension additionally spans each {@link org.commonmark.ext.gfm.tables.TableRow}
     * (header + body rows). We stamp every such node so the client gets fine-grained, per-element anchors:
     * a heading, a paragraph, a list item, a table row, a code block, a thematic break, etc.
     *
     * <p>The only nodes skipped are wrappers that would merely duplicate a line already covered by a more
     * specific element: the {@link Document} itself, and a table's {@link TableHead} / {@link TableBody} /
     * {@link TableCell} (the {@link org.commonmark.ext.gfm.tables.TableRow} already carries the row's line;
     * per-cell anchors would just repeat it). Nested anchors are expected and harmless — the client
     * decorates only the innermost anchored element, so a list item and its paragraph, or a table and its
     * rows, still yield exactly one "+" per visible line. Escaping/URL sanitisation are unaffected: this
     * provider only adds a numeric line hint, never markup or user text.
     */
    private static final class SourceLineAttributeProvider implements AttributeProvider {
        @Override
        public void setAttributes(Node node, String tagName, Map<String, String> attributes) {
            if (node instanceof Document
                    || node instanceof TableHead
                    || node instanceof TableBody
                    || node instanceof TableCell) {
                return;
            }
            List<SourceSpan> spans = node.getSourceSpans();
            if (spans.isEmpty()) {
                return;
            }
            attributes.put("data-source-line", String.valueOf(spans.get(0).getLineIndex() + 1));
        }
    }
}
