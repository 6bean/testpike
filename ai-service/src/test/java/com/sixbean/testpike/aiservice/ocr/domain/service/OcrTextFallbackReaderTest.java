package com.sixbean.testpike.aiservice.ocr.domain.service;

import com.sixbean.testpike.aiservice.ocr.api.dto.OcrFallbackOutcome;
import com.sixbean.testpike.aiservice.ocr.api.dto.OcrTextLine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Vision-less OCR fallback reader : the shared
 * component every fallback application point consumes. Contract ladder:
 * ① text-dense input (line count at or above the caller's floor) renders a
 * grid layout wrapped in the {@code <ocr_derived>} provenance markers so
 * the outbound prompt can always tell OCR transcription from a vision
 * observation or anchored source text;
 * ② line-sparse input is an honest "not enough lines" verdict — never a
 * forced interpretation, and the count rides the outcome for the caller's
 * refusal sentence;
 * ③ engine failure keeps its named reason (fail closed, never a silent
 * empty result);
 * ④ the markers wrap exactly once: the text inside is the rendered rows,
 * rows of a multi-cell line join with the grid separator.
 */
class OcrTextFallbackReaderTest {

    private static byte[] FAKE_BYTES = new byte[] {1, 2, 3};

    /** One row of the source image: same y band, two x bands. */
    private static OcrTextLine line(String text, double x, double y) {
        return new OcrTextLine(text, 0.95, List.of(
                List.of(x, y), List.of(x + 40, y),
                List.of(x + 40, y + 14), List.of(x, y + 14)), false);
    }

    private static OcrTextFallbackReader readerWith(List<OcrTextLine> lines) {
        OcrEngineService engine = mock(OcrEngineService.class);
        when(engine.extract(FAKE_BYTES)).thenReturn(lines);
        return new OcrTextFallbackReader(engine, new GridAssembler(0.5));
    }

    @Test
    void denseInputRendersMarkedGrid() {
        OcrTextFallbackReader reader = readerWith(List.of(
                line("Username", 10, 20), line("alice", 200, 21),
                line("Token", 10, 40), line("abc123", 200, 41),
                line("Expires", 10, 60)));
        OcrFallbackOutcome outcome = reader.read(FAKE_BYTES, 3);
        assertTrue(outcome.textDense());
        assertNull(outcome.failureReason());
        assertEquals(5, outcome.lineCount());
        String text = outcome.derivedText();
        assertTrue(text.startsWith("<ocr_derived>"), "opening provenance marker missing");
        assertTrue(text.endsWith("</ocr_derived>"), "closing provenance marker missing");
        assertTrue(text.contains("Username | alice"), "same-row cells must join with the grid separator: " + text);
        assertTrue(text.contains("Token | abc123"), "second row lost: " + text);
        assertTrue(text.contains("Expires"), "single-cell row lost: " + text);
    }

    @Test
    void sparseInputIsAnHonestVerdictNotAForcedRead() {
        OcrTextFallbackReader reader = readerWith(List.of(line("ok", 10, 20), line("only", 10, 40)));
        OcrFallbackOutcome outcome = reader.read(FAKE_BYTES, 3);
        assertFalse(outcome.textDense());
        assertNull(outcome.derivedText());
        assertNull(outcome.failureReason());
        assertEquals(2, outcome.lineCount());
    }

    @Test
    void engineFailureKeepsItsNamedReason() {
        OcrEngineService engine = mock(OcrEngineService.class);
        when(engine.extract(FAKE_BYTES)).thenThrow(new IllegalStateException("OCR worker 进程不可用"));
        OcrFallbackOutcome outcome = new OcrTextFallbackReader(engine, new GridAssembler(0.5))
                .read(FAKE_BYTES, 3);
        assertFalse(outcome.textDense());
        assertTrue(outcome.failureReason().contains("OCR worker 进程不可用"),
                "refusal must name the engine reason, never fold it into silence");
    }

    @Test
    void emptyExtractionCountsZeroNotCrash() {
        OcrTextFallbackReader reader = readerWith(List.of());
        OcrFallbackOutcome outcome = reader.read(FAKE_BYTES, 3);
        assertFalse(outcome.textDense());
        assertEquals(0, outcome.lineCount());
        assertNull(outcome.failureReason());
    }
}
