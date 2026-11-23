package com.sixbean.testpike.aiservice.ocr.api.dto;

/**
 * Verdict of one vision-less OCR fallback read .
 * Three shapes, kept separate so a caller can tell "not enough text to read
 * honestly" from "the engine failed" and never fold either into silence:
 * <ul>
 * <li>{@link #dense} — the image carried at least the caller's line floor;
 * {@code derivedText} is the grid rendering wrapped in the
 * {@code <ocr_derived>} provenance markers (supplement item 1: OCR text
 * must always be identifiable as transcription inside the outbound
 * prompt — character-level errors possible, citations declare their
 * source, anchored material wins on conflict);</li>
 * <li>{@link #sparse} — below the floor: an honest refusal input, the
 * count rides along for the refusal sentence (sparse lines must never be
 * force-interpreted);</li>
 * <li>{@link #failed} — the engine itself said no; the named reason is
 * kept verbatim for the degraded line.</li>
 * </ul>
 *
 * @param textDense true only in the dense shape
 * @param derivedText marked transcription block (null unless dense)
 * @param lineCount usable OCR lines the engine returned (0 when the engine failed before counting)
 * @param failureReason named engine failure (null unless failed)
 */
public record OcrFallbackOutcome(boolean textDense, String derivedText, int lineCount,
                                 String failureReason) {

    public static OcrFallbackOutcome dense(String markedText, int lineCount) {
        return new OcrFallbackOutcome(true, markedText, lineCount, null);
    }

    public static OcrFallbackOutcome sparse(int lineCount) {
        return new OcrFallbackOutcome(false, null, lineCount, null);
    }

    public static OcrFallbackOutcome failed(String reason) {
        return new OcrFallbackOutcome(false, null, 0, reason);
    }
}
