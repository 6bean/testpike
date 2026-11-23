package com.sixbean.testpike.aiservice.ocr.domain.service;

import com.sixbean.testpike.aiservice.ocr.api.dto.OcrFallbackOutcome;
import com.sixbean.testpike.aiservice.ocr.api.dto.OcrGridCell;
import com.sixbean.testpike.aiservice.ocr.api.dto.OcrTextLine;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Shared vision-less image reader (BF batch half-slice B1, R40 single
 * source): every application point that used to refuse when
 * {@code ai.vision.model} was blank now routes through this one component —
 * OCR pass plus the shared {@link GridAssembler} geometry, never a private
 * second clustering implementation.
 * <p>Posture contract:
 * <ul>
 * <li>text-dense (usable lines at or above the caller's floor, from the
 * hot-tunable {@code ai.ocr.fallback-line-min}) → the rows render into a
 * transcription block wrapped in {@code <ocr_derived>} markers, so the
 * outbound prompt can always distinguish OCR text from a vision
 * observation or anchored source (supplement item 1);</li>
 * <li>line-sparse → an honest sparse verdict: the caller refuses with the
 * line count named, this component never force-interprets a picture with
 * two lines on it;</li>
 * <li>engine down → the named reason travels untouched (fail closed).</li>
 * </ul>
 * The marker strings are public constants: the application points and their
 * contract tests reference the same pair — a copy per call site is exactly
 * the drift this batch forbids.
 */
@Service
public class OcrTextFallbackReader {

    /** Opening provenance marker of an OCR transcription block. */
    public static final String MARKER_OPEN = "<ocr_derived>";
    /** Closing provenance marker of an OCR transcription block. */
    public static final String MARKER_CLOSE = "</ocr_derived>";
    /** Separator joining cells of one visual row (grid shape kept). */
    private static final String CELL_SEPARATOR = " | ";
    /** Prompt-weight bound of one marked transcription block. */
    public static final int TRANSCRIPTION_MAX_CHARS = 12000;

    private final OcrEngineService ocrEngineService;
    private final GridAssembler gridAssembler;

    public OcrTextFallbackReader(OcrEngineService ocrEngineService, GridAssembler gridAssembler) {
        this.ocrEngineService = ocrEngineService;
        this.gridAssembler = gridAssembler;
    }

    /**
     * OCR one image and judge density against {@code minLines} (the caller
     * reads the floor from sys config so the threshold stays hot-tunable per
     * environment). A null image is an honest failure naming itself, never
     * an empty transcription.
     */
    public OcrFallbackOutcome read(byte[] imageBytes, int minLines) {
        if (imageBytes == null || imageBytes.length == 0) {
            return OcrFallbackOutcome.failed("图片字节为空，OCR 引擎无输入");
        }
        List<OcrTextLine> lines;
        try {
            lines = ocrEngineService.extract(imageBytes);
        } catch (RuntimeException engineFailure) {
            return OcrFallbackOutcome.failed(engineFailure.getMessage() == null
                    ? engineFailure.getClass().getSimpleName() : engineFailure.getMessage());
        }
        int count = lines == null ? 0 : lines.size();
        if (count < Math.max(1, minLines)) {
            return OcrFallbackOutcome.sparse(count);
        }
        return OcrFallbackOutcome.dense(wrap(renderRows(lines)), count);
    }

    /** Rows top-to-bottom, cells left-to-right, one line per visual row. */
    private String renderRows(List<OcrTextLine> lines) {
        StringBuilder text = new StringBuilder();
        for (List<OcrGridCell> row : gridAssembler.clusterRows(lines)) {
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) {
                    text.append(CELL_SEPARATOR);
                }
                text.append(row.get(i).text());
            }
            text.append('\n');
        }
        return text.toString().stripTrailing();
    }

    private static String wrap(String rendered) {
        return MARKER_OPEN + "\n" + rendered + "\n" + MARKER_CLOSE;
    }

    /**
     * Bound one marked transcription for prompt weight while keeping the
     * provenance pair well-formed (supplement item 1: a cut block must still
     * open and close with the same marker pair). Single source — both image
     * application points consume this instead of writing their own truncation
     * (R40).
     */
    public static String boundTranscription(String derivedText) {
        if (derivedText == null || derivedText.length() <= TRANSCRIPTION_MAX_CHARS) {
            return derivedText == null ? "" : derivedText;
        }
        String body = derivedText.startsWith(MARKER_OPEN) && derivedText.endsWith(MARKER_CLOSE)
                ? derivedText.substring(MARKER_OPEN.length(),
                        derivedText.length() - MARKER_CLOSE.length())
                : derivedText;
        int cap = TRANSCRIPTION_MAX_CHARS - MARKER_OPEN.length() - MARKER_CLOSE.length()
                - "\n…（转写超长已截断）".length() - 2;
        return wrap(body.strip().substring(0, Math.max(0, cap)) + "…（转写超长已截断）");
    }

    /**
     * The honest one-line notice that this round is machine transcription,
     * not image understanding — the R32 dual-factor guidance (settings path
     * literal + the model key literal) plus the character-level-error and
     * cite-your-source declaration (supplement item 1). Single source so the
     * two application points cannot drift apart.
     */
    public static String degradedNotice(String modelKeyLiteral) {
        return "（本轮为截图文字提取（OCR）非图像理解：视觉模型未配置（系统设置-"
                + modelKeyLiteral + "）。提取内容为机器转写非原文，字符级可错，"
                + "引用须声明来源，与可锚定原文冲突时以原文为准。）";
    }

    /** Honest sparse verdict sentence naming the line count (never force-interpret). */
    public static String sparseReason(int lineCount) {
        return "仅提取到 " + lineCount + " 行文字，不足以如实转写，未强行解读画面";
    }
}
