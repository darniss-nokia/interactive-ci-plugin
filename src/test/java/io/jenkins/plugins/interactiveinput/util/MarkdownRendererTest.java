// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Security-focused tests for {@link MarkdownRenderer}: user-supplied markdown must never inject raw
 * HTML or unsafe URL schemes into the modal DOM (§7.5).
 */
class MarkdownRendererTest {

    @Test
    void nullAndBlankRenderToEmptyString() {
        assertEquals("", MarkdownRenderer.render(null));
        assertEquals("", MarkdownRenderer.render(""));
    }

    @Test
    void rendersBasicMarkdown() {
        String html = MarkdownRenderer.render("**bold** and _em_");
        assertTrue(html.contains("<strong>bold</strong>"), html);
        assertTrue(html.contains("<em>em</em>"), html);
    }

    @Test
    void escapesRawHtmlSoScriptCannotExecute() {
        String html = MarkdownRenderer.render("<script>alert('xss')</script>");
        assertFalse(html.contains("<script>"), "raw <script> must be escaped, was: " + html);
        assertTrue(html.contains("&lt;script&gt;"), "expected escaped script tag, was: " + html);
    }

    @Test
    void stripsJavascriptUrlScheme() {
        String html = MarkdownRenderer.render("[click](javascript:alert('xss'))");
        assertFalse(html.contains("javascript:"), "javascript: scheme must be sanitised, was: " + html);
    }

    @Test
    void keepsSafeHttpLinks() {
        String html = MarkdownRenderer.render("[docs](https://www.jenkins.io/)");
        assertTrue(html.contains("href=\"https://www.jenkins.io/\""), html);
    }

    @Test
    void renderWithSourceLinesTagsTopLevelBlocksWithTheirSourceLine() {
        // Line 1 = heading, line 2 = blank, line 3 = paragraph.
        String html = MarkdownRenderer.renderWithSourceLines("# Heading\n\nA paragraph.");
        assertTrue(html.contains("data-source-line=\"1\""), "heading anchors to line 1: " + html);
        assertTrue(html.contains("data-source-line=\"3\""), "paragraph anchors to line 3: " + html);
    }

    @Test
    void renderWithSourceLinesKeepsTheSameSanitisation() {
        String html = MarkdownRenderer.renderWithSourceLines("<script>alert(1)</script>\n\n[l](javascript:alert(1))");
        assertFalse(html.contains("<script>"), "raw <script> must be escaped: " + html);
        assertFalse(html.contains("javascript:"), "javascript: scheme must be sanitised: " + html);
    }

    @Test
    void renderWithSourceLinesHandlesNullAndBlank() {
        assertEquals("", MarkdownRenderer.renderWithSourceLines(null));
        assertEquals("", MarkdownRenderer.renderWithSourceLines(""));
    }

    // ---- Per-element source-line anchoring (line-level inline commenting) ----
    // Every commentable element (list item, table row, ...) — not just the top-level block — must carry
    // its own data-source-line so a reviewer can comment the exact line without a manual line picker.

    @Test
    void renderWithSourceLinesTagsEachListItemWithItsOwnLine() {
        String html = MarkdownRenderer.renderWithSourceLines("- one\n- two\n- three");
        assertTrue(html.contains("<li"), "list items present: " + html);
        assertTrue(html.contains("data-source-line=\"1\""), "item 1 anchors to line 1: " + html);
        assertTrue(html.contains("data-source-line=\"2\""), "item 2 anchors to line 2: " + html);
        assertTrue(html.contains("data-source-line=\"3\""), "item 3 anchors to line 3: " + html);
    }

    @Test
    void renderWithSourceLinesTagsEachTableRowWithItsOwnLine() {
        // Table: line 1 header, line 2 separator (not rendered), line 3 + line 4 body rows.
        String html = MarkdownRenderer.renderWithSourceLines("| A | B |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |");
        assertTrue(html.contains("<tr"), "table rows present: " + html);
        assertTrue(html.contains("data-source-line=\"1\""), "header row anchors to line 1: " + html);
        assertTrue(html.contains("data-source-line=\"3\""), "first body row anchors to line 3: " + html);
        assertTrue(html.contains("data-source-line=\"4\""), "second body row anchors to line 4: " + html);
    }

    @Test
    void renderWithSourceLinesDoesNotAnchorIndividualTableCells() {
        // Cells share their row's line; anchoring each cell would clutter the row with duplicate markers.
        String html = MarkdownRenderer.renderWithSourceLines("| A | B |\n|---|---|\n| 1 | 2 |");
        assertFalse(html.contains("<td data-source-line"), "data cells must not be individually anchored: " + html);
        assertFalse(html.contains("<th data-source-line"), "header cells must not be individually anchored: " + html);
    }

    // ---- GitHub-Flavored Markdown extensions (tables / strikethrough / autolink) ----
    // Regression: GFM tables are not in the CommonMark core spec, so before the TablesExtension was
    // registered a pipe table rendered as a literal "|"-delimited paragraph (the Interactive View bug).

    @Test
    void rendersGfmPipeTableAsHtmlTable() {
        String md = "| Aspect | Value |\n|---|---|\n| length | 1..255 |\n| wildcard | FF |";
        String html = MarkdownRenderer.render(md);
        assertTrue(html.contains("<table>"), "expected an HTML table, was: " + html);
        assertTrue(html.contains("<thead>") && html.contains("<th>Aspect</th>"), "expected a header row: " + html);
        assertTrue(html.contains("<tbody>") && html.contains("<td>length</td>"), "expected a body cell: " + html);
        // The bug symptom was the literal separator row leaking through as text.
        assertFalse(html.contains("|---|"), "the delimiter row must not appear as literal text: " + html);
    }

    @Test
    void tableColumnAlignmentIsHonoured() {
        String md = "| L | C | R |\n|:--|:-:|--:|\n| a | b | c |";
        String html = MarkdownRenderer.render(md);
        assertTrue(html.contains("align=\"center\""), "center column must carry align=center: " + html);
        assertTrue(html.contains("align=\"right\""), "right column must carry align=right: " + html);
    }

    @Test
    void tableCellHtmlIsStillEscaped() {
        // Extensions must not weaken escaping: a <script> inside a table cell stays inert.
        String md = "| col |\n|-----|\n| <script>alert(1)</script> |";
        String html = MarkdownRenderer.render(md);
        assertTrue(html.contains("<table>"), "still a table: " + html);
        assertFalse(html.contains("<script>"), "raw <script> in a cell must be escaped: " + html);
        assertTrue(html.contains("&lt;script&gt;"), "expected escaped script tag: " + html);
    }

    @Test
    void renderWithSourceLinesRendersTables() {
        // The document body uses the source-line variant; it must render tables too (not just render()).
        String md = "# T\n\n| A | B |\n|---|---|\n| 1 | 2 |";
        String html = MarkdownRenderer.renderWithSourceLines(md);
        // The source-line variant tags top-level blocks, so the table opens as <table data-source-line=..>.
        assertTrue(html.contains("<table"), "source-line variant must also render tables: " + html);
        assertTrue(
                html.contains("data-source-line"), "the table becomes a commentable, source-anchored block: " + html);
        assertFalse(html.contains("|---|"), "no literal delimiter row: " + html);
    }

    // ---- Malformed table repair (the ATLAS "Planned Test Types" table did not render) ----
    // A generator emitted a table whose header had one more column than its delimiter row; GFM only
    // recognises a table when header and delimiter cell counts match, so it degraded to a "|" paragraph.

    @Test
    void repairsMismatchedTableDelimiterSoItRenders() {
        // 4-column header, but the delimiter row only has 3 '---' cells (off by one) -> without repair
        // this whole block renders as a literal "|"-delimited paragraph.
        String md = "| A | B | C | D |\n|---|---|---|\n| 1 | 2 | 3 | 4 |";
        String html = MarkdownRenderer.render(md);
        assertTrue(html.contains("<table>"), "mismatched table must be repaired and rendered: " + html);
        assertTrue(html.contains("<th>A</th>") && html.contains("<th>D</th>"), "all 4 headers present: " + html);
        assertTrue(html.contains("<td>4</td>"), "the 4th column only renders once the delimiter is padded: " + html);
        assertFalse(html.contains("|---|"), "no literal delimiter row may leak through: " + html);
    }

    @Test
    void repairedTablePreservesColumnAlignment() {
        // Header has 4 columns; delimiter has only 3 alignment cells (left, center, right) -> repaired to
        // 4 columns, and the center/right alignment of the surviving cells must be preserved.
        String md = "| L | C | R | X |\n|:--|:-:|--:|\n| a | b | c | d |";
        String html = MarkdownRenderer.render(md);
        assertTrue(html.contains("<table>"), "mismatched aligned table must be repaired: " + html);
        assertTrue(html.contains("align=\"center\""), "center alignment must survive the repair: " + html);
        assertTrue(html.contains("align=\"right\""), "right alignment must survive the repair: " + html);
    }

    @Test
    void repairedTableRendersViaSourceLineVariantAndKeepsLineNumbers() {
        // The document body uses renderWithSourceLines; the repair must work there too and must NOT change
        // the line count (the heading stays on line 1, the table header on line 3) so anchoring is intact.
        String md = "# T\n\n| A | B | C |\n|---|---|\n| 1 | 2 | 3 |";
        String html = MarkdownRenderer.renderWithSourceLines(md);
        assertTrue(html.contains("<table"), "source-line variant must also repair and render the table: " + html);
        assertTrue(html.contains("data-source-line=\"1\""), "heading still anchors to line 1: " + html);
        assertTrue(html.contains("data-source-line=\"3\""), "table still anchors to its header line 3: " + html);
        assertFalse(html.contains("|---|"), "no literal delimiter row: " + html);
    }

    @Test
    void wellFormedTableIsLeftUntouchedByTheRepairPass() {
        // A correct table must render exactly as before (repair is a no-op when counts already match).
        String md = "| Aspect | Value |\n|---|---|\n| length | 1..255 |";
        String html = MarkdownRenderer.render(md);
        assertTrue(html.contains("<table>") && html.contains("<th>Aspect</th>"), "still a table: " + html);
        assertTrue(html.contains("<td>length</td>"), "still a body cell: " + html);
    }

    @Test
    void pipeTextWithoutADelimiterRowIsNotTurnedIntoATable() {
        // Safety: a paragraph containing pipes followed by a setext underline / thematic break ('---' has
        // no pipe) must NOT be converted into a table by the repair pass.
        String md = "Costs 5 | 10 | 20 dollars\n---\nmore text";
        String html = MarkdownRenderer.render(md);
        assertFalse(html.contains("<table"), "a dashline with no pipe must never become a table: " + html);
    }

    // ---- Un-fencing a bare-fenced pipe table (Interactive View: "table inside a bullet not rendered") ----
    // Item #2: some report generators wrap a table in a bare ``` fence (no language). CommonMark then
    // (correctly per spec) renders it verbatim as <pre><code> "|"-delimited text. A bare fence whose body is
    // exactly a pipe table is unwrapped so it renders as a real <table>. A fence WITH a language, or a bare
    // fence with any non-table content, must still be preserved verbatim as code.

    @Test
    void bareFencedPipeTableIsUnwrappedAndRenders() {
        String md = "```\n| A | B |\n|---|---|\n| 1 | 2 |\n```";
        String html = MarkdownRenderer.render(md);
        assertTrue(html.contains("<table>"), "a bare-fenced pipe table must render as a table: " + html);
        assertTrue(html.contains("<th>A</th>") && html.contains("<td>1</td>"), "header + body cells: " + html);
        assertFalse(html.contains("<pre>"), "the fence must be unwrapped, not kept as a code block: " + html);
        assertFalse(html.contains("|---|"), "no literal delimiter row may leak through: " + html);
    }

    @Test
    void bareFencedPipeTableInsideListItemRenders() {
        // The exact reported case: the table is fenced inside a bullet. This also proves CommonMark renders
        // a table nested within an <li> (blanking the fence lines keeps the table indented under the item).
        String md = "- Item with a table:\n    ```\n    | A | B |\n    | --- | --- |\n    | 1 | 2 |\n    ```";
        String html = MarkdownRenderer.render(md);
        assertTrue(html.contains("<li>"), "list item present: " + html);
        assertTrue(html.contains("<table>"), "the in-bullet fenced table must render as a table: " + html);
        assertTrue(html.contains("<th>A</th>") && html.contains("<td>1</td>"), "header + body cells: " + html);
        assertFalse(html.contains("<pre>"), "the fence must be unwrapped, not kept as a code block: " + html);
    }

    @Test
    void languagedFenceWithPipeTableBodyIsLeftAsCode() {
        // Safety: a fence WITH a language is an intentional code sample and must stay verbatim, never a table.
        String md = "```python\n| A | B | C |\n|---|---|---|\n| 1 | 2 | 3 |\n```";
        String html = MarkdownRenderer.render(md);
        assertFalse(html.contains("<table"), "a languaged code fence must not become a table: " + html);
        assertTrue(html.contains("language-python"), "the language class must be preserved: " + html);
        assertTrue(html.contains("|---|"), "the literal delimiter row must survive inside the code block: " + html);
    }

    @Test
    void bareFenceWithNonTableContentIsLeftAsCode() {
        // Safety: a bare fence whose body is not purely a pipe table (has a non-pipe line) stays a code block.
        String md = "```\n| A | B |\n|---|---|\nplain code line\n```";
        String html = MarkdownRenderer.render(md);
        assertFalse(html.contains("<table"), "a fence with a non-table line must not become a table: " + html);
        assertTrue(html.contains("plain code line"), "the code body must survive verbatim: " + html);
        assertTrue(html.contains("|---|"), "the literal delimiter row must survive inside the code block: " + html);
    }

    @Test
    void unfencedTableKeepsSourceLineNumbersStable() {
        // Blanking (not removing) the fence lines must preserve the line count so data-source-line stays
        // valid: heading on line 1, the fence opens on line 3, so the unwrapped table header is still line 4.
        String md = "# T\n\n```\n| A | B |\n|---|---|\n| 1 | 2 |\n```";
        String html = MarkdownRenderer.renderWithSourceLines(md);
        assertTrue(html.contains("<table"), "the source-line variant must also unwrap the fenced table: " + html);
        assertTrue(html.contains("data-source-line=\"1\""), "heading still anchors to line 1: " + html);
        assertTrue(
                html.contains("data-source-line=\"4\""), "the unwrapped table header still anchors to line 4: " + html);
        assertFalse(html.contains("<pre>"), "no code block remains: " + html);
    }

    @Test
    void rendersStrikethrough() {
        String html = MarkdownRenderer.render("this is ~~struck~~ text");
        assertTrue(html.contains("<del>struck</del>"), "~~x~~ must render as <del>: " + html);
    }

    @Test
    void autolinksBareUrls() {
        String html = MarkdownRenderer.render("see https://www.jenkins.io/ for docs");
        assertTrue(html.contains("href=\"https://www.jenkins.io/\""), "bare URL must become a link: " + html);
    }

    @Test
    void autolinkStillSanitisesUnsafeSchemes() {
        // A bare, unsafe scheme must not become a clickable javascript: link.
        String html = MarkdownRenderer.render("click [here](javascript:alert(1)) or ~~nope~~");
        assertFalse(html.contains("javascript:alert"), "javascript: must still be sanitised: " + html);
    }
}
