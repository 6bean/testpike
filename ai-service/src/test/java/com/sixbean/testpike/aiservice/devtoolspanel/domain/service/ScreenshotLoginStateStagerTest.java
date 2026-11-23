package com.sixbean.testpike.aiservice.devtoolspanel.domain.service;

import com.sixbean.testpike.aiservice.devtoolspanel.api.dto.PanelStageOutcome;
import com.sixbean.testpike.aiservice.devtoolspanel.domain.service.impl.DevToolsPanelParseServiceImpl;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.ocr.api.dto.OcrTextLine;
import com.sixbean.testpike.aiservice.ocr.domain.service.GridAssembler;
import com.sixbean.testpike.aiservice.ocr.domain.service.OcrEngineService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Screenshot login-state stager contract .
 * Boundary posture: the OCR engine and the core door are the two isolated
 * seams; the panel structure parser is the REAL piece (one grid aligner,
 * R40). Red lines under test: values go IN through the door and never come
 * BACK OUT (the outcome carries only kind/name/origin markers), a closed
 * switch performs zero OCR and zero door calls, and any leg failure degrades
 * to a silent pass-through (the dialog turn must survive the enhancement).
 */
class ScreenshotLoginStateStagerTest {

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final OcrEngineService ocrEngineService = mock(OcrEngineService.class);
    private final DevToolsPanelParseService parseService =
            new DevToolsPanelParseServiceImpl(new GridAssembler(0.5));
    private final ScreenshotLoginStateStager stager =
            new ScreenshotLoginStateStager(coreGateway, ocrEngineService, parseService);

    /** One OCR cell box laid out like the real Cookies band (piece B geometry). */
    private static OcrTextLine cell(String text, double x1, double x2, double y) {
        return cell(text, x1, x2, y, false);
    }

    private static OcrTextLine cell(String text, double x1, double x2, double y, boolean annotated) {
        return new OcrTextLine(text, 0.99, List.of(
                List.of(x1, y), List.of(x2, y), List.of(x2, y + 18), List.of(x1, y + 18)), annotated);
    }

    /** Cookies table: one clean red-marked row + one ellipsis-truncated row. */
    private static List<OcrTextLine> cookiesPanel() {
        return List.of(
                cell("Name", 400, 480, 120),
                cell("Value", 500, 760, 120),
                cell("Domain", 780, 900, 120),
                cell("Path", 920, 980, 120),
                cell("Expires", 1000, 1090, 120),
                cell("SESSION", 400, 470, 145, true),
                cell("9f8e7d6c5b4a", 500, 620, 145),
                cell(".example.com", 780, 890, 145),
                cell("/", 920, 930, 145),
                cell("2026-12-31T08:00:00Z", 1000, 1090, 145),
                cell("JSESSIONID", 400, 500, 170),
                cell("a1b2c3d4…", 500, 640, 170),
                cell("例子.测试", 780, 880, 170),
                cell("/api", 920, 960, 170),
                cell("Session", 1000, 1060, 170));
    }

    private void gateOn() {
        when(coreGateway.getSysConfig(ScreenshotLoginStateStager.KEY_LOGIN_STATE_GATE))
                .thenReturn("1");
    }

    @Test
    void closedGateIsSilentPassThroughWithZeroOcrAndZeroStageCalls() {
        when(coreGateway.getSysConfig(ScreenshotLoginStateStager.KEY_LOGIN_STATE_GATE))
                .thenReturn("0");
        PanelStageOutcome outcome = stager.stageFromScreenshot(77L, new byte[]{1, 2, 3});
        assertEquals(PanelStageOutcome.NONE, outcome);
        assertFalse(outcome.staged());
        verifyNoInteractions(ocrEngineService);
        verify(coreGateway, never()).stageScreenshotSlot(any(), anyLong(), anyList());
    }

    @Test
    void blankSessionOrEmptyBytesShortCircuitsBeforeTheGateIsEvenRead() {
        assertEquals(PanelStageOutcome.NONE, stager.stageFromScreenshot(0L, new byte[]{1}));
        assertEquals(PanelStageOutcome.NONE, stager.stageFromScreenshot(77L, null));
        verifyNoInteractions(coreGateway, ocrEngineService);
    }

    @Test
    void panelWithoutAnyRecognisedRowNeverOpensTheStageDoor() {
        gateOn();
        when(ocrEngineService.extract(any())).thenReturn(List.of(cell("about:blank", 0, 100, 0)));
        PanelStageOutcome outcome = stager.stageFromScreenshot(77L, new byte[]{1});
        assertEquals(PanelStageOutcome.NONE, outcome);
        verify(coreGateway, never()).stageScreenshotSlot(any(), anyLong(), anyList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void parsedRowsParkThroughTheDoorWithValuesInAndValueFreeMetaBack() {
        gateOn();
        when(ocrEngineService.extract(any())).thenReturn(cookiesPanel());
        when(coreGateway.stageScreenshotSlot(eq(ScreenshotLoginStateStager.CHANNEL_CHAT_SCREENSHOT),
                eq(77L), anyList())).thenReturn("tok-123");

        PanelStageOutcome outcome = stager.stageFromScreenshot(77L, new byte[]{1, 2});

        assertTrue(outcome.staged());
        assertEquals("tok-123", outcome.token());
        assertEquals(1, outcome.candidateCount());
        assertEquals(1, outcome.truncatedSuspectCount());

        // The door payload carries the values (they transit memory + Redis).
        ArgumentCaptor<List<Map<String, Object>>> rows = ArgumentCaptor.forClass(List.class);
        verify(coreGateway).stageScreenshotSlot(eq(ScreenshotLoginStateStager.CHANNEL_CHAT_SCREENSHOT),
                eq(77L), rows.capture());
        Map<String, Object> row = rows.getValue().get(0);
        assertEquals("cookie", row.get("kind"));
        assertEquals("SESSION", row.get("name"));
        assertEquals(".example.com", row.get("origin"));
        assertEquals("9f8e7d6c5b4a", row.get("value"));
        assertEquals(Instant.parse("2026-12-31T08:00:00Z").getEpochSecond(),
                row.get("expiresAtEpochSecond"));

        // The returned summary is value-free: names/origins/markers only — a
        // credential string anywhere in it would poison the model prompt.
        String rendered = outcome.token() + String.join("|", outcome.metadataLines());
        assertFalse(rendered.contains("9f8e7d6c5b4a"));
        assertFalse(rendered.contains("a1b2c3d4"));
        assertTrue(rendered.contains("SESSION"));
        assertTrue(rendered.contains(".example.com"));
        assertTrue(rendered.contains("标红"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void sessionAndUnparseableExpiryTextStageAsNullEpochHonestly() {
        gateOn();
        List<OcrTextLine> panel = List.of(
                cell("Name", 400, 480, 120),
                cell("Value", 500, 760, 120),
                cell("Domain", 780, 900, 120),
                cell("Expires", 1000, 1090, 120),
                cell("sid", 400, 430, 145),
                cell("v-42", 500, 560, 145),
                cell("a.example.com", 780, 890, 145),
                cell("明天下午", 1000, 1090, 145));
        when(ocrEngineService.extract(any())).thenReturn(panel);
        when(coreGateway.stageScreenshotSlot(any(), anyLong(), anyList())).thenReturn("tok-9");

        stager.stageFromScreenshot(77L, new byte[]{1});

        ArgumentCaptor<List<Map<String, Object>>> rows = ArgumentCaptor.forClass(List.class);
        verify(coreGateway).stageScreenshotSlot(any(), eq(77L), rows.capture());
        assertNull(rows.getValue().get(0).get("expiresAtEpochSecond"));
    }

    @Test
    void ocrOrDoorFailureDegradesToNoneInsteadOfTakingTheTurnDown() {
        gateOn();
        when(ocrEngineService.extract(any())).thenThrow(new IllegalStateException("ocr worker down"));
        assertEquals(PanelStageOutcome.NONE, stager.stageFromScreenshot(77L, new byte[]{1}));

        // doReturn form: re-stubbing through when(...) would fire the previous
        // throwing stub outside the stager's own try boundary.
        org.mockito.Mockito.doReturn(cookiesPanel()).when(ocrEngineService).extract(any());
        when(coreGateway.stageScreenshotSlot(any(), anyLong(), anyList()))
                .thenThrow(new RuntimeException("core door unreachable"));
        assertEquals(PanelStageOutcome.NONE, stager.stageFromScreenshot(77L, new byte[]{1}));
    }
}
