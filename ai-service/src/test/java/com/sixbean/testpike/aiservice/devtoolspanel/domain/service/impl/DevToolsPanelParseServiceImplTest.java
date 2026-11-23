package com.sixbean.testpike.aiservice.devtoolspanel.domain.service.impl;

import com.sixbean.testpike.aiservice.devtoolspanel.api.dto.DevToolsPanelParseResult;
import com.sixbean.testpike.aiservice.devtoolspanel.api.dto.LoginStateCandidate;
import com.sixbean.testpike.aiservice.ocr.api.dto.OcrTextLine;
import com.sixbean.testpike.aiservice.ocr.domain.service.GridAssembler;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L2 contract tests for the DevTools panel structure parser .
 *
 * <p>Fixtures are synthetic OCR line sets carrying the pixel geometry a real
 * Chrome DevTools Application panel produces (Names/Values band for Local
 * Storage, the Name..SameSite header band for Cookies). Honest labelling per
 * the adjudication file: these are same-layout approximations, not captures of
 * a live DevTools window - the real-shot anchor set is tracked separately and
 * its gap is registered, not waived.
 */
class DevToolsPanelParseServiceImplTest {

    private final DevToolsPanelParseServiceImpl service =
            new DevToolsPanelParseServiceImpl(new GridAssembler(0.5));

    /** One OCR cell box: full-width band between x1..x2 at row top y, height 18px. */
    private static OcrTextLine cell(String text, double x1, double x2, double y) {
        return cell(text, x1, x2, y, false);
    }

    /** Same cell with the red-annotation flag set explicitly. */
    private static OcrTextLine cell(String text, double x1, double x2, double y, boolean annotated) {
        return new OcrTextLine(text, 0.99, List.of(
                List.of(x1, y), List.of(x2, y), List.of(x2, y + 18), List.of(x1, y + 18)), annotated);
    }

    private static List<OcrTextLine> localStoragePanel() {
        return List.of(
                cell("Local Storage", 20, 150, 30),
                cell("https://shop.example.com", 20, 230, 60),
                cell("Cookies", 20, 100, 90),
                cell("Names", 400, 560, 120),
                cell("Values", 580, 1100, 120),
                cell("token", 400, 460, 145),
                cell("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9", 580, 1090, 145),
                cell("lang", 400, 440, 170),
                cell("zh-CN", 580, 640, 170),
                cell("cartDraft", 400, 500, 195),
                cell("{\"items\":[1,2,3],\"coupon\":\"SUMMER…", 580, 1100, 195));
    }

    private static List<OcrTextLine> cookiesPanel(double headerY) {
        return List.of(
                cell("Name", 400, 480, headerY),
                cell("Value", 500, 760, headerY),
                cell("Domain", 780, 900, headerY),
                cell("Path", 920, 980, headerY),
                cell("Expires", 1000, 1090, headerY),
                cell("Size", 1110, 1150, headerY),
                cell("HttpOnly", 1170, 1240, headerY),
                cell("SameSite", 1260, 1340, headerY),
                cell("SESSION", 400, 470, headerY + 25),
                cell("9f8e7d6c5b4a", 500, 620, headerY + 25),
                cell(".example.com", 780, 890, headerY + 25),
                cell("/", 920, 930, headerY + 25),
                cell("Session", 1000, 1060, headerY + 25),
                cell("21", 1110, 1130, headerY + 25),
                cell("✓", 1175, 1190, headerY + 25),
                cell("Lax", 1260, 1290, headerY + 25),
                cell("JSESSIONID", 400, 500, headerY + 50),
                cell("a1b2c3d4…", 500, 640, headerY + 50),
                cell("例子.测试", 780, 880, headerY + 50),
                cell("/api", 920, 960, headerY + 50),
                cell("2026-12-31T08:00:00Z", 1000, 1090, headerY + 50));
    }

    @Test
    void localStoragePanelYieldsCandidatesWithNearestOriginAboveHeader() {
        DevToolsPanelParseResult result = service.parse(localStoragePanel());
        assertEquals(2, result.candidates().size(), "cartDraft must not pass as a clean candidate");
        LoginStateCandidate token = result.candidates().get(0);
        assertEquals("local_storage", token.kind());
        assertEquals("token", token.name());
        assertEquals("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9", token.value());
        assertEquals("https://shop.example.com", token.origin());
        assertNull(token.expiresAt());
        assertFalse(token.httpOnly());
        assertEquals("lang", result.candidates().get(1).name());
        // sidebar/tree text must never surface as a row
        assertTrue(result.candidates().stream().noneMatch(c -> c.name().contains("Storage")
                || c.name().equals("Cookies")), "cells outside the table columns must be dropped");
    }

    @Test
    void cookiesPanelMapsColumnsWithDomainExpiresHttpOnly() {
        DevToolsPanelParseResult result = service.parse(cookiesPanel(120));
        List<LoginStateCandidate> cookies = result.candidates().stream()
                .filter(c -> "cookie".equals(c.kind())).toList();
        assertEquals(1, cookies.size(), "truncated JSESSIONID row must fail closed out of candidates");
        LoginStateCandidate session = cookies.get(0);
        assertEquals("SESSION", session.name());
        assertEquals("9f8e7d6c5b4a", session.value());
        assertEquals(".example.com", session.origin());
        assertEquals("Session", session.expiresAt());
        assertTrue(session.httpOnly());
    }

    @Test
    void truncatedValueFailsClosedIntoSuspectsOnly() {
        DevToolsPanelParseResult result = service.parse(cookiesPanel(120));
        assertEquals(1, result.truncatedSuspects().size());
        LoginStateCandidate suspect = result.truncatedSuspects().get(0);
        assertEquals("JSESSIONID", suspect.name());
        assertEquals("例子.测试", suspect.origin(), "chinese-domain row is still reported for retake");
        assertTrue(result.candidates().stream().noneMatch(c -> "JSESSIONID".equals(c.name())),
                "suspected truncated rows never enter the accepted set");
    }

    @Test
    void localStorageTruncatedValueAlsoFailsClosed() {
        DevToolsPanelParseResult result = service.parse(localStoragePanel());
        assertEquals(1, result.truncatedSuspects().size());
        assertEquals("cartDraft", result.truncatedSuspects().get(0).name());
        assertEquals("{\"items\":[1,2,3],\"coupon\":\"SUMMER…", result.truncatedSuspects().get(0).value());
    }

    @Test
    void rowWithoutValueCellProducesNothing() {
        List<OcrTextLine> lines = List.of(
                cell("Names", 400, 560, 120),
                cell("Values", 580, 1100, 120),
                cell("theme", 400, 460, 145));
        DevToolsPanelParseResult result = service.parse(lines);
        assertTrue(result.candidates().isEmpty());
        assertTrue(result.truncatedSuspects().isEmpty());
    }

    @Test
    void unrelatedTextWithoutHeaderYieldsEmptyResult() {
        DevToolsPanelParseResult result = service.parse(List.of(
                cell("Application", 300, 420, 40),
                cell("token", 400, 460, 145),
                cell("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9", 580, 1090, 145)));
        assertTrue(result.candidates().isEmpty());
        assertTrue(result.truncatedSuspects().isEmpty());
    }

    @Test
    void stackedPanelRegionsInOnePassAreBothParsed() {
        List<OcrTextLine> lines = new java.util.ArrayList<>(localStoragePanel());
        lines.addAll(cookiesPanel(300));
        DevToolsPanelParseResult result = service.parse(lines);
        assertEquals(3, result.candidates().size(), "2 localStorage + 1 clean cookie");
        assertEquals(2, result.truncatedSuspects().size());
        long cookieKind = result.candidates().stream().filter(c -> "cookie".equals(c.kind())).count();
        assertEquals(1, cookieKind);
    }

    @Test
    void fragmentedValueCellsMergeWithinColumn() {
        List<OcrTextLine> lines = List.of(
                cell("Names", 400, 560, 120),
                cell("Values", 580, 1100, 120),
                cell("token", 400, 460, 145),
                cell("eyJhbGciOi", 580, 640, 145),
                cell("JIUzI1NiIs", 642, 700, 145));
        DevToolsPanelParseResult result = service.parse(lines);
        assertEquals(1, result.candidates().size());
        assertEquals("eyJhbGciOiJIUzI1NiIs", result.candidates().get(0).value(),
                "gap-adjacent fragments concatenate verbatim (no ellipsis inserted)");
        assertFalse(result.candidates().isEmpty());
    }

    @Test
    void redMarkedRowCarriesAnnotatedFlagThroughAssembly() {
        List<OcrTextLine> lines = List.of(
                cell("Names", 400, 560, 120),
                cell("Values", 580, 1100, 120),
                cell("token", 400, 460, 145, true),
                cell("eyJhbG...VCJ9", 580, 1090, 145, true),
                cell("lang", 400, 440, 170),
                cell("zh-CN", 580, 640, 170));
        DevToolsPanelParseResult result = service.parse(lines);
        assertEquals(2, result.candidates().size());
        assertTrue(result.candidates().get(0).annotated(), "marked name/value cells mark the candidate row");
        assertFalse(result.candidates().get(1).annotated(), "unmarked rows keep the flag false");
    }

    @Test
    void valueCellsWiderThanTheHeaderBandStillBelongToTheirColumn() {
        // Live-window fixture : OCR header caption boxes
        // are only as wide as their text - "Values" spans 79px here - while a
        // 32-hex token box overflows to 422px and the session box to 183px.
        // Measuring overlap against the cell's own width dropped both rows.
        List<OcrTextLine> lines = List.of(
                cell("Local Storage", 20, 150, 30),
                cell("https://shop.example.com", 20, 230, 60),
                cell("Names", 400, 460, 120),
                cell("Values", 580, 659, 120),
                cell("auth_token", 400, 478, 145),
                cell("0f1e2d3c4b5a69788796a5b4c3d2e1f0", 580, 1002, 145, true),
                cell("session_id", 400, 483, 170),
                cell("550e8400-e29b-41d4-a716", 580, 763, 170),
                cell("lang", 400, 432, 195),
                cell("zh-CN", 580, 628, 195));
        DevToolsPanelParseResult result = service.parse(lines);
        assertEquals(3, result.candidates().size(),
                "overflowing value cells must stay in the column they start in");
        LoginStateCandidate auth = result.candidates().stream()
                .filter(c -> c.name().equals("auth_token")).findFirst().orElseThrow();
        assertEquals("0f1e2d3c4b5a69788796a5b4c3d2e1f0", auth.value());
        assertTrue(auth.annotated(), "the red mark on the overflowing row must survive");
        LoginStateCandidate session = result.candidates().stream()
                .filter(c -> c.name().equals("session_id")).findFirst().orElseThrow();
        assertEquals("550e8400-e29b-41d4-a716", session.value());
        assertFalse(session.annotated());
        // A name cell must never leak into the narrow Values column just
        // because it ends past the band's left edge.
        assertEquals("auth_token", result.candidates().get(0).name(), "rows keep document order");
    }

    @Test
    void annotatedFlagNeverRescuesTruncatedSuspect() {
        List<OcrTextLine> lines = List.of(
                cell("Names", 400, 560, 120),
                cell("Values", 580, 1100, 120),
                cell("cartDraft", 400, 500, 145, true),
                cell("{\"items\":[1,2,3],\"coupon\":\"SUMMER…", 580, 1100, 145, true));
        DevToolsPanelParseResult result = service.parse(lines);
        assertTrue(result.candidates().isEmpty(), "a red mark must not promote a truncated row");
        assertEquals(1, result.truncatedSuspects().size());
        assertTrue(result.truncatedSuspects().get(0).annotated(),
                "the flag rides along so piece D can point the retake hint at marked rows");
    }
}
