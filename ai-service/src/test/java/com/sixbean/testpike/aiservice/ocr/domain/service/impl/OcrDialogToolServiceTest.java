package com.sixbean.testpike.aiservice.ocr.domain.service.impl;

import com.sixbean.testpike.aiservice.aichat.domain.component.ChatImageLedgerScanner;
import com.sixbean.testpike.aiservice.aichat.domain.component.FailureScreenshotBytesFetcher;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.ocr.api.dto.OcrDialogGridForm;
import com.sixbean.testpike.aiservice.ocr.api.dto.OcrDialogGridOutcome;
import com.sixbean.testpike.aiservice.ocr.api.dto.OcrDialogTextOutcome;
import com.sixbean.testpike.aiservice.ocr.api.dto.OcrDialogToolRequest;
import com.sixbean.testpike.aiservice.ocr.api.dto.OcrTextLine;
import com.sixbean.testpike.aiservice.ocr.domain.service.GridAssembler;
import com.sixbean.testpike.aiservice.ocr.domain.service.OcrEngineService;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Contract tests for the two read-only dialog OCR doors (BF batch
 * : handle ownership refusals fire before any byte is read, the
 * character door answers with engine lines plus the red-marked list, and the
 * grid door classifies table/text/grid off the shared GridAssembler output.
 */
class OcrDialogToolServiceTest {

    private static final String KEY = "chat-image/5/2026/11/ab.png";
    private static final byte[] PIXELS = new byte[] {1, 2, 3, 4};

    private OcrEngineService engine;
    private CoreGateway coreGateway;
    private FailureScreenshotBytesFetcher bytesFetcher;
    private ChatImageLedgerScanner scanner;
    private OcrDialogToolServiceImpl service;

    @BeforeEach
    void setUp() {
        engine = mock(OcrEngineService.class);
        coreGateway = mock(CoreGateway.class);
        bytesFetcher = mock(FailureScreenshotBytesFetcher.class);
        scanner = mock(ChatImageLedgerScanner.class);
        service = new OcrDialogToolServiceImpl(engine, coreGateway, bytesFetcher,
                new GridAssembler(0.5), scanner);
    }

    @Test
    void textExtractOwnedHandleReturnsLinesCoordinatesConfidenceAndAnnotatedList() {
        OcrTextLine plain = line("username", 0, 0, 50, 10, false);
        OcrTextLine marked = line("Abc123", 60, 0, 40, 10, true);
        stubOwnedRead(List.of(plain, marked));

        OcrDialogTextOutcome outcome = service.textExtract(anchored());

        assertEquals(2, outcome.lineCount());
        assertEquals(List.of(plain, marked), outcome.lines());
        assertEquals(List.of("Abc123"), outcome.annotatedLines());
        assertEquals(0.93, marked.score(), 0.0001);
        assertFalse(marked.box().isEmpty());
        verify(engine).extract(PIXELS);
    }

    @Test
    void unownedHandleIsRefusedNamingTheHandleBeforeAnyByteRead() {
        when(scanner.ownedImageKeys(9L, 77L, OcrDialogToolServiceImpl.LEDGER_SCAN_LIMIT))
                .thenReturn(Set.of("chat-image/5/other.png"));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.textExtract(anchored()));

        assertTrue(ex.getMessage().contains(KEY), ex.getMessage());
        assertTrue(ex.getMessage().contains("不属于当前对话会话"), ex.getMessage());
        verify(coreGateway, never()).chatImageUrl(anyLong(), anyString());
        verifyNoInteractions(bytesFetcher, engine);
    }

    @Test
    void displayTailHandleResolvesToItsBookedKeyBeforeTheRead() {
        String booked = "chat-image/5/2026/11/a1b2c3-ab.png";
        when(scanner.ownedImageKeys(9L, 77L, OcrDialogToolServiceImpl.LEDGER_SCAN_LIMIT))
                .thenReturn(Set.of(booked));
        when(coreGateway.chatImageUrl(5L, booked)).thenReturn("http://door/img");
        when(bytesFetcher.fetch(eq("http://door/img"), anyInt())).thenReturn(PIXELS);
        when(engine.extract(PIXELS)).thenReturn(List.of(line("ok", 0, 0, 9, 9, false)));

        OcrDialogTextOutcome outcome = service.textExtract(
                new OcrDialogToolRequest("ab.png", 77L, 5L, 9L));

        assertEquals(1, outcome.lineCount());
        verify(coreGateway).chatImageUrl(5L, booked);
    }

    @Test
    void ambiguousTailHandleIsRefusedBeforeAnyByteRead() {
        when(scanner.ownedImageKeys(9L, 77L, OcrDialogToolServiceImpl.LEDGER_SCAN_LIMIT))
                .thenReturn(Set.of("chat-image/5/2026/11/a1-ab.png",
                        "chat-image/5/2026/11/b2_ab.png"));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.textExtract(new OcrDialogToolRequest("ab.png", 77L, 5L, 9L)));

        assertTrue(ex.getMessage().contains("多张登记图片"), ex.getMessage());
        verify(coreGateway, never()).chatImageUrl(anyLong(), anyString());
        verifyNoInteractions(bytesFetcher, engine);
    }

    @Test
    void missingServerStampedAnchorIsRefusedWithZeroLedgerReads() {
        List<OcrDialogToolRequest> unanchored = List.of(
                new OcrDialogToolRequest(KEY, null, 5L, 9L),
                new OcrDialogToolRequest(KEY, 0L, 5L, 9L),
                new OcrDialogToolRequest(KEY, 77L, null, 9L),
                new OcrDialogToolRequest(KEY, 77L, 5L, -1L));

        for (OcrDialogToolRequest request : unanchored) {
            ServiceException ex = assertThrows(ServiceException.class,
                    () -> service.textExtract(request));
            assertTrue(ex.getMessage().contains("会话锚点"), ex.getMessage());
        }
        verifyNoInteractions(scanner, coreGateway, bytesFetcher, engine);
    }

    @Test
    void blankHandleIsRefusedWithoutOpeningTheLedger() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.gridRead(new OcrDialogToolRequest("  ", 77L, 5L, 9L)));

        assertTrue(ex.getMessage().contains("objectKey"), ex.getMessage());
        verifyNoInteractions(scanner, coreGateway, bytesFetcher, engine);
    }

    @Test
    void unreachableLedgerDoorRefusesInsteadOfPretendingAbsence() {
        when(scanner.ownedImageKeys(9L, 77L, OcrDialogToolServiceImpl.LEDGER_SCAN_LIMIT))
                .thenThrow(new RuntimeException("connection refused"));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.textExtract(anchored()));

        assertTrue(ex.getMessage().contains("暂不可达"), ex.getMessage());
        verifyNoInteractions(bytesFetcher, engine);
    }

    @Test
    void gridReadRegularMatrixYieldsTableWithGfmMarkdownAndEscapedPipes() {
        List<OcrTextLine> grid = List.of(
                line("name", 0, 0, 40, 10, false),
                line("value", 60, 0, 40, 10, false),
                line("time", 120, 0, 30, 10, false),
                line("tok", 0, 20, 40, 10, false),
                line("ab|cd", 60, 20, 40, 10, false),
                line("10:00", 120, 20, 30, 10, false));
        stubOwnedRead(grid);

        OcrDialogGridOutcome outcome = service.gridRead(anchored());

        assertEquals(OcrDialogGridForm.TABLE, outcome.form());
        assertEquals(6, outcome.lineCount());
        assertEquals(2, outcome.rows().size());
        String markdown = outcome.markdown();
        assertTrue(markdown.contains("| name | value | time |"), markdown);
        assertTrue(markdown.contains("| --- | --- | --- |"), markdown);
        assertTrue(markdown.contains("ab\\|cd"), markdown);
    }

    @Test
    void gridReadSingleColumnRowsYieldTextFormWithoutMarkdownClaim() {
        stubOwnedRead(List.of(
                line("first line", 0, 0, 200, 10, false),
                line("second line", 0, 20, 200, 10, false),
                line("third line", 0, 40, 200, 10, false)));

        OcrDialogGridOutcome outcome = service.gridRead(anchored());

        assertEquals(OcrDialogGridForm.TEXT, outcome.form());
        assertNull(outcome.markdown());
        assertEquals(3, outcome.lineCount());
        assertEquals(List.of(List.of("first line"), List.of("second line"),
                List.of("third line")), outcome.rows());
    }

    @Test
    void gridReadRaggedRowsYieldGridForm() {
        stubOwnedRead(List.of(
                line("a", 0, 0, 40, 10, false),
                line("b", 60, 0, 40, 10, false),
                line("c", 120, 0, 40, 10, false),
                line("d", 0, 20, 40, 10, false),
                line("e", 60, 20, 90, 10, false)));

        OcrDialogGridOutcome outcome = service.gridRead(anchored());

        assertEquals(OcrDialogGridForm.GRID, outcome.form());
        assertNull(outcome.markdown());
        assertEquals(2, outcome.rows().size());
        assertEquals(3, outcome.rows().get(0).size());
        assertEquals(2, outcome.rows().get(1).size());
    }

    @Test
    void engineFailurePropagatesItsNamedReasonAndNeverSilentEmpty() {
        when(scanner.ownedImageKeys(9L, 77L, OcrDialogToolServiceImpl.LEDGER_SCAN_LIMIT))
                .thenReturn(Set.of(KEY));
        when(coreGateway.chatImageUrl(5L, KEY)).thenReturn("http://door/img");
        when(bytesFetcher.fetch(eq("http://door/img"), anyInt())).thenReturn(PIXELS);
        when(engine.extract(any())).thenThrow(new ServiceException("OCR 引擎不可用: worker exit 2"));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.textExtract(anchored()));

        assertTrue(ex.getMessage().contains("worker exit 2"), ex.getMessage());
    }

    private void stubOwnedRead(List<OcrTextLine> lines) {
        when(scanner.ownedImageKeys(9L, 77L, OcrDialogToolServiceImpl.LEDGER_SCAN_LIMIT))
                .thenReturn(Set.of(KEY));
        when(coreGateway.chatImageUrl(5L, KEY)).thenReturn("http://door/img");
        when(bytesFetcher.fetch(anyString(), anyInt())).thenReturn(PIXELS);
        when(engine.extract(PIXELS)).thenReturn(lines);
    }

    private static OcrDialogToolRequest anchored() {
        return new OcrDialogToolRequest(KEY, 77L, 5L, 9L);
    }

    private static OcrTextLine line(String text, double x, double y,
                                    double w, double h, boolean annotated) {
        return new OcrTextLine(text, 0.93, List.of(
                List.of(x, y), List.of(x + w, y),
                List.of(x + w, y + h), List.of(x, y + h)), annotated);
    }
}
