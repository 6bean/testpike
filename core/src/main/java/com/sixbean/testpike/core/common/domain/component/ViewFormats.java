package com.sixbean.testpike.core.common.domain.component;

import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared view formatting component for list and detail presentation layers, extracting preview and time formatting for cross-domain reuse.
 * <p> bodyPreview truncation: list and version-chain entries must not return full body content (information display surface).
 * Time is uniformly formatted as 'yyyy-MM-dd HH:mm:ss' at second precision for human-readable display without millisecond noise.
 * <p>Preview reads as prose (AR stage-4): the list preview is the only body
 * text users see without opening the editor, so heading paragraphs,
 * frontmatter blocks, parenthesized internal annotations (stage tags,
 * hot-config key families, contract anchors) and markdown markup noise are
 * stripped down to the first prose paragraph. Plain single-paragraph bodies
 * keep their verbatim text with only the length truncation — zero drift for
 * existing list consumers.
 *
 */
public final class ViewFormats {

    /** Body preview truncation length. */
    public static final int PREVIEW_MAX = 500;

    /** Fixed-width second-precision display format (LocalDateTime.toString omits zero-valued fields). */
    private static final DateTimeFormatter TS_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Wrapped markdown markup (bold/italic/inline code), stripped from preview. */
    private static final Pattern MARKUP_INLINE = Pattern.compile("\\*{1,3}|_{1,3}|`");

    /** Leading list/quote marker ("1. ", "- ", "&gt; "), stripped from preview. */
    private static final Pattern LEADING_LIST_MARK = Pattern.compile("^\\s*(?:[0-9]+[.)]|[-*+|>])\\s+");

    /** Parenthesized note; dropped when it carries a latin word (stage tag, config key family, contract anchor). */
    private static final Pattern PAREN_NOTE = Pattern.compile("[（(][^（()）]*[）)]");

    /** Frontmatter block (the --- key/value head of market-import payloads), skipped in preview. */
    private static final Pattern FRONTMATTER_LEAD = Pattern.compile("\\A-{3,}?\\n.*?\\n-{3,}?\\n", Pattern.DOTALL);

    private ViewFormats() {
    }

    /**
     * Body preview: first prose paragraph, de-markuped, truncated with the
     * ellipsis; null passes through. Plain text without paragraph structure
     * (no blank-line split) keeps its verbatim body with truncation only.
     */
    public static String preview(String bodyText) {
        if (bodyText == null) {
            return null;
        }
        String prose = toProse(bodyText);
        return prose.length() <= PREVIEW_MAX ? prose : prose.substring(0, PREVIEW_MAX) + "…";
    }

    /** Markdown body → prose preview (heading paragraphs and frontmatter skipped, latin-word notes dropped). */
    private static String toProse(String bodyText) {
        String normalized = bodyText.replace("\r\n", "\n").replace('\r', '\n');
        normalized = FRONTMATTER_LEAD.matcher(normalized).replaceFirst("");
        String firstParagraph = null;
        for (String block : normalized.split("\\n{2,}")) {
            String trimmed = block.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.matches("-{3,}")) {
                continue;
            }
            firstParagraph = trimmed;
            break;
        }
        if (firstParagraph == null) {
            // No prose paragraph (heading-only or separator-only body): first non-heading line; blank body stays verbatim
            for (String line : normalized.split("\\n")) {
                String trimmed = line.strip();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#") && !trimmed.matches("-{3,}")) {
                    firstParagraph = trimmed;
                    break;
                }
            }
            if (firstParagraph == null) {
                return normalized.strip();
            }
        }
        return stripNotes(firstParagraph);
    }

    /** Strip inline markup and internal parenthesized annotations. */
    private static String stripNotes(String paragraph) {
        String single = paragraph.replaceAll("\\n\\s*", " ").strip();
        single = LEADING_LIST_MARK.matcher(single).replaceFirst("");
        Matcher paren = PAREN_NOTE.matcher(single);
        StringBuilder kept = new StringBuilder();
        int cursor = 0;
        while (paren.find()) {
            String inner = paren.group().substring(1, paren.group().length() - 1);
            // A latin-carrying note is a machine marker (stage tag / config key / contract anchor) — drop it; a pure-CJK note is prose — keep it
            kept.append(single, cursor, paren.start());
            if (!inner.matches(".*[A-Za-z].*")) {
                kept.append(paren.group());
            }
            cursor = paren.end();
        }
        kept.append(single.substring(cursor));
        return MARKUP_INLINE.matcher(kept).replaceAll("").replaceAll("\\s{2,}", " ").strip();
    }

    /** Convert Timestamp to a 'yyyy-MM-dd HH:mm:ss' string; pass null through unchanged. */
    public static String formatTs(Timestamp ts) {
        return ts == null ? null : TS_FORMATTER.format(ts.toLocalDateTime());
    }
}
