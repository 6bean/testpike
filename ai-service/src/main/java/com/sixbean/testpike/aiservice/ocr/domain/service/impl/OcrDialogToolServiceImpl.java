package com.sixbean.testpike.aiservice.ocr.domain.service.impl;

import com.sixbean.testpike.aiservice.aichat.domain.component.ChatImageLedgerScanner;
import com.sixbean.testpike.aiservice.aichat.domain.component.FailureScreenshotBytesFetcher;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.domain.component.SysConfigInts;
import com.sixbean.testpike.aiservice.ocr.api.dto.OcrDialogGridForm;
import com.sixbean.testpike.aiservice.ocr.api.dto.OcrDialogGridOutcome;
import com.sixbean.testpike.aiservice.ocr.api.dto.OcrDialogTextOutcome;
import com.sixbean.testpike.aiservice.ocr.api.dto.OcrDialogToolRequest;
import com.sixbean.testpike.aiservice.ocr.api.dto.OcrTextLine;
import com.sixbean.testpike.aiservice.ocr.domain.service.GridAssembler;
import com.sixbean.testpike.aiservice.ocr.domain.service.OcrDialogToolService;
import com.sixbean.testpike.aiservice.ocr.domain.service.OcrEngineService;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Dialog OCR doors implementation : the ownership
 * seam refuses before object storage is touched, then one shared read leg
 * (presigned door + bounded fetch + engine) feeds the character door
 * verbatim and the grid door through the shared {@link GridAssembler}.
 */
@Service
public class OcrDialogToolServiceImpl implements OcrDialogToolService {

    /** How many booked image ledger rows the ownership scan reads back newest-first. */
    static final int LEDGER_SCAN_LIMIT = 30;

    /** Same hot-tunable image size cap as the vision round-trip (one knob, two readers). */
    static final String KEY_IMAGE_MAX_BYTES = "ai.vision.failure-screenshot-max-bytes";
    static final int DEFAULT_IMAGE_MAX_BYTES = 8 * 1024 * 1024;

    private final OcrEngineService engine;
    private final CoreGateway coreGateway;
    private final FailureScreenshotBytesFetcher bytesFetcher;
    private final GridAssembler assembler;
    private final ChatImageLedgerScanner ledgerScanner;

    public OcrDialogToolServiceImpl(OcrEngineService engine,
                                    CoreGateway coreGateway,
                                    FailureScreenshotBytesFetcher bytesFetcher,
                                    GridAssembler assembler,
                                    ChatImageLedgerScanner ledgerScanner) {
        this.engine = engine;
        this.coreGateway = coreGateway;
        this.bytesFetcher = bytesFetcher;
        this.assembler = assembler;
        this.ledgerScanner = ledgerScanner;
    }

    @Override
    public OcrDialogTextOutcome textExtract(OcrDialogToolRequest request) {
        List<OcrTextLine> lines = readOwnedLines(request);
        List<String> annotated = lines.stream()
                .filter(OcrTextLine::annotated)
                .map(OcrTextLine::text)
                .toList();
        return new OcrDialogTextOutcome(lines, annotated, lines.size());
    }

    @Override
    public OcrDialogGridOutcome gridRead(OcrDialogToolRequest request) {
        List<OcrTextLine> lines = readOwnedLines(request);
        List<List<String>> rowTexts = assembler.clusterRows(lines).stream()
                .map(assembler::rowCells)
                .filter(row -> !row.isEmpty())
                .toList();
        int maxCols = rowTexts.stream().mapToInt(List::size).max().orElse(0);
        if (maxCols < 2) {
            return new OcrDialogGridOutcome(OcrDialogGridForm.TEXT, null, rowTexts, lines.size());
        }
        boolean regular = rowTexts.size() >= 2
                && rowTexts.stream().allMatch(row -> row.size() == maxCols);
        return regular
                ? new OcrDialogGridOutcome(OcrDialogGridForm.TABLE,
                        gfmTable(rowTexts), rowTexts, lines.size())
                : new OcrDialogGridOutcome(OcrDialogGridForm.GRID,
                        null, rowTexts, lines.size());
    }

    /**
     * One shared read leg behind both doors: owned-bytes in, engine lines out.
     * Engine failures keep their named reason (never folded into a silent
     * empty result — an empty bag would read back as "the picture was blank").
     */
    private List<OcrTextLine> readOwnedLines(OcrDialogToolRequest request) {
        String bookedKey = resolveOwnedKey(request);
        String url = coreGateway.chatImageUrl(request.projectId(), bookedKey);
        int maxBytes = SysConfigInts.positiveInt(
                coreGateway.getSysConfig(KEY_IMAGE_MAX_BYTES), DEFAULT_IMAGE_MAX_BYTES);
        byte[] image = bytesFetcher.fetch(url, maxBytes);
        List<OcrTextLine> lines;
        try {
            lines = engine.extract(image);
        } catch (ServiceException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new ServiceException("图片文字识别失败：" + ex.getMessage());
        }
        if (lines == null) {
            throw new ServiceException("图片文字识别失败：OCR 引擎无结果返回（不静默当作空白图）");
        }
        return List.copyOf(lines);
    }

    /** GFM table from a regular matrix; cell pipes escaped, line breaks folded. */
    private static String gfmTable(List<List<String>> rowTexts) {
        StringBuilder markdown = new StringBuilder();
        markdown.append(tableRow(rowTexts.get(0))).append('\n');
        markdown.append("| ")
                .append(String.join(" | ",
                        Collections.nCopies(rowTexts.get(0).size(), "---")))
                .append(" |\n");
        for (List<String> row : rowTexts.subList(1, rowTexts.size())) {
            markdown.append(tableRow(row)).append('\n');
        }
        return markdown.toString();
    }

    private static String tableRow(List<String> cells) {
        return "| " + String.join(" | ",
                cells.stream().map(OcrDialogToolServiceImpl::escapeCell).toList()) + " |";
    }

    private static String escapeCell(String cell) {
        return cell.replace("|", "\\|").replace("\n", "<br>");
    }

    /**
     * Fail-closed identity seam: every refusal happens before object storage
     * is touched, and "could not read the ledger" is a refusal, never a pass.
     * The caller may hold either the booked ledger key or only the display
     * tail shown in the observation block, so an exact miss gets one more
     * deterministic step: unique tail match against the booked file names
     * (booking prefixes separate with '-' or '_'). Ambiguous or absent tails
     * stay refused — the door never guesses which picture was meant.
     */
    private String resolveOwnedKey(OcrDialogToolRequest request) {
        if (request == null || request.objectKey() == null || request.objectKey().isBlank()) {
            throw new ServiceException("缺少图片句柄 objectKey：请指定本对话内贴入的一张图片，其它来源不可读");
        }
        if (request.chatSessionId() == null || request.chatSessionId() <= 0
                || request.operatorId() == null || request.operatorId() <= 0
                || request.projectId() == null || request.projectId() <= 0) {
            throw new ServiceException("对话 OCR 缺少服务端盖章的会话锚点（chatSessionId/projectId/operatorId），"
                    + "已拒绝读取图片句柄 " + request.objectKey());
        }
        Set<String> owned;
        try {
            owned = ledgerScanner.ownedImageKeys(request.operatorId(),
                    request.chatSessionId(), LEDGER_SCAN_LIMIT);
        } catch (RuntimeException ex) {
            throw new ServiceException("本会话图片台账暂不可达，无法核验图片句柄 "
                    + request.objectKey() + " 的归属，已拒绝读取（无法核验时按拒绝处理）: "
                    + ex.getMessage());
        }
        if (owned != null && owned.contains(request.objectKey())) {
            return request.objectKey();
        }
        List<String> tails = owned == null ? List.of() : owned.stream()
                .filter(key -> tailMatches(key, request.objectKey()))
                .toList();
        if (tails.size() == 1) {
            return tails.get(0);
        }
        if (tails.size() > 1) {
            throw new ServiceException("图片句柄 " + request.objectKey()
                    + " 命中多张登记图片，无法确定读取哪一张，已拒绝读取；"
                    + "请改用观察块「会话句柄」出示的完整图片地址");
        }
        throw new ServiceException("图片句柄 " + request.objectKey()
                + " 不属于当前对话会话的贴图集合，已拒绝读取");
    }

    /** True when the booked file name ends with the display tail after its booking prefix. */
    private static boolean tailMatches(String bookedKey, String handle) {
        int slash = bookedKey.lastIndexOf('/');
        String fileName = slash < 0 ? bookedKey : bookedKey.substring(slash + 1);
        return fileName.endsWith("-" + handle) || fileName.endsWith("_" + handle);
    }
}
