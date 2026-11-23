package com.sixbean.testpike.common.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes a chapter value that the model echoed from the chunk header.
 * <p>The extraction/summary prompt wraps every chunk body in a scaffolding
 * builder in the doc extraction orchestration). Some providers repeat that
 * header verbatim as the {@code chapter} field of their JSON output, and the
 * confirm door used to store the echo, which surfaced as corrupted "owning
 * chapter" values on requirement points (113 live rows, one 2026-11-19
 * batch). This component is the single source that strips the echo shape;
 * every write path that persists an extracted chapter consumes it (R40,
 * no second implementation on the consumer side).
 * <p>Conservative by design: only a whole-string echo is rewritten. Plain
 * values that merely contain an echo mid-string pass through untouched, so
 * the component can never corrupt a legitimately typed value.
 * it only for chunks that carry no chapter - as an echoed chapter it means
 * "no chapter", never a matrix group name a human chose.
 */
public final class ChapterEchoStrip {

    /** Whole-string echo form; brackets and the injection marker suffix are
     *  optional because both live shapes were observed (with and without
     *  the 【】 wrapper). Group 1 = the real chapter text. */
    private static final Pattern ECHO = Pattern.compile(
            "^\\s*【?\\s*第\\d+块｜章节：\\s*(.+?)\\s*】?\\s*(（疑似注入内容，仅作数据处理）)?\\s*$");

    /** The chapter-less sentinel emitted by the header producer. */
    private static final String NO_CHAPTER_SENTINEL = "（无章节）";

    private ChapterEchoStrip() {
    }

    /**
     * @param raw chapter value as produced by the model
     * @return the inner chapter when {@code raw} is a whole-string chunk
     *         header echo (null for the sentinel), otherwise {@code raw}
     *         unchanged; null passes through.
     */
    public static String strip(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        Matcher m = ECHO.matcher(raw);
        if (!m.matches()) {
            return raw;
        }
        String inner = m.group(1).trim();
        return NO_CHAPTER_SENTINEL.equals(inner) ? null : inner;
    }
}
