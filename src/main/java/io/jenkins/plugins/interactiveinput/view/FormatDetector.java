// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.view;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Locale;
import java.util.Map;

/**
 * Maps a file name to a {@link ReviewDocument} render format and a Prism language hint.
 *
 * <p>The format decides how the review page presents the snapshot: {@code markdown} is rendered to
 * safe HTML, everything else (including HTML and any programming language) is shown as
 * <em>escaped, syntax-highlighted source</em> — the file is never executed. The language string is a
 * Prism grammar id used by {@code prism-api} for highlighting (see
 * <a href="https://prismjs.com/#supported-languages">Prism languages</a>); {@code "none"} disables
 * highlighting for plain text.
 */
public final class FormatDetector {

    /** Immutable pairing of a render format and a Prism language id. */
    public static final class Detected {
        @NonNull
        public final String format;

        @NonNull
        public final String language;

        Detected(@NonNull String format, @NonNull String language) {
            this.format = format;
            this.language = language;
        }
    }

    // Prism grammar id per known extension. Kept small and explicit; unknown extensions fall back to
    // plain text so nothing ever breaks — highlighting is best-effort, escaping is guaranteed.
    private static final Map<String, String> CODE_LANGUAGES = Map.ofEntries(
            Map.entry("java", "java"),
            Map.entry("groovy", "groovy"),
            Map.entry("kt", "kotlin"),
            Map.entry("kts", "kotlin"),
            Map.entry("scala", "scala"),
            Map.entry("py", "python"),
            Map.entry("rb", "ruby"),
            Map.entry("go", "go"),
            Map.entry("rs", "rust"),
            Map.entry("c", "c"),
            Map.entry("h", "c"),
            Map.entry("cpp", "cpp"),
            Map.entry("cc", "cpp"),
            Map.entry("hpp", "cpp"),
            Map.entry("cs", "csharp"),
            Map.entry("php", "php"),
            Map.entry("js", "javascript"),
            Map.entry("jsx", "jsx"),
            Map.entry("mjs", "javascript"),
            Map.entry("ts", "typescript"),
            Map.entry("tsx", "tsx"),
            Map.entry("json", "json"),
            Map.entry("yml", "yaml"),
            Map.entry("yaml", "yaml"),
            Map.entry("toml", "toml"),
            Map.entry("xml", "markup"),
            Map.entry("xsd", "markup"),
            Map.entry("svg", "markup"),
            Map.entry("sql", "sql"),
            Map.entry("sh", "bash"),
            Map.entry("bash", "bash"),
            Map.entry("css", "css"),
            Map.entry("scss", "scss"),
            Map.entry("ini", "ini"),
            Map.entry("conf", "ini"),
            Map.entry("properties", "properties"),
            Map.entry("dockerfile", "docker"),
            Map.entry("tf", "hcl"),
            Map.entry("gradle", "groovy"),
            // Robot Framework test suites (.robot) and resource files (.resource) share one Prism grammar.
            Map.entry("robot", "robotframework"),
            Map.entry("resource", "robotframework"));

    private FormatDetector() {}

    /**
     * @param fileName a file name or path (only the extension / base name is inspected)
     * @return the detected format and Prism language (never {@code null})
     */
    @NonNull
    public static Detected detect(@NonNull String fileName) {
        String base = baseName(fileName).toLowerCase(Locale.ROOT);
        String ext = extension(base);

        if ("md".equals(ext) || "markdown".equals(ext) || "mdown".equals(ext)) {
            return new Detected(ReviewDocument.FORMAT_MARKDOWN, "markdown");
        }
        if ("html".equals(ext) || "htm".equals(ext) || "xhtml".equals(ext)) {
            // HTML is shown as escaped, highlighted source (never executed) — hence FORMAT_HTML, which
            // the page treats like code with the "markup" grammar.
            return new Detected(ReviewDocument.FORMAT_HTML, "markup");
        }
        // Common no-extension review files are plain text.
        if ("dockerfile".equals(base)) {
            return new Detected(ReviewDocument.FORMAT_CODE, "docker");
        }
        if (ext.isEmpty() || "txt".equals(ext) || "log".equals(ext) || "text".equals(ext)) {
            return new Detected(ReviewDocument.FORMAT_TEXT, "none");
        }
        String lang = CODE_LANGUAGES.get(ext);
        if (lang != null) {
            return new Detected(ReviewDocument.FORMAT_CODE, lang);
        }
        return new Detected(ReviewDocument.FORMAT_TEXT, "none");
    }

    /**
     * Resolve an explicit user-supplied {@code format} override to a {@link Detected}, falling back to
     * extension detection for the language hint.
     *
     * @param format   one of {@code markdown|html|text|code} (case-insensitive); blank means auto
     * @param fileName used to derive the language hint (and the format when {@code format} is blank)
     */
    @NonNull
    public static Detected resolve(@CheckForNull String format, @NonNull String fileName) {
        Detected auto = detect(fileName);
        if (format == null || format.trim().isEmpty()) {
            return auto;
        }
        switch (format.trim().toLowerCase(Locale.ROOT)) {
            case ReviewDocument.FORMAT_MARKDOWN:
                return new Detected(ReviewDocument.FORMAT_MARKDOWN, "markdown");
            case ReviewDocument.FORMAT_HTML:
                return new Detected(ReviewDocument.FORMAT_HTML, "markup");
            case ReviewDocument.FORMAT_CODE:
                return new Detected(ReviewDocument.FORMAT_CODE, "none".equals(auto.language) ? "none" : auto.language);
            case ReviewDocument.FORMAT_TEXT:
                return new Detected(ReviewDocument.FORMAT_TEXT, "none");
            default:
                return auto;
        }
    }

    @NonNull
    private static String baseName(@NonNull String path) {
        String p = path.replace('\\', '/');
        int slash = p.lastIndexOf('/');
        return slash >= 0 ? p.substring(slash + 1) : p;
    }

    @NonNull
    private static String extension(@NonNull String base) {
        int dot = base.lastIndexOf('.');
        return dot > 0 && dot < base.length() - 1 ? base.substring(dot + 1) : "";
    }
}
