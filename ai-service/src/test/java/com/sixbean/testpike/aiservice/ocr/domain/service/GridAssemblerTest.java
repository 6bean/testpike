package com.sixbean.testpike.aiservice.ocr.domain.service;

import com.sixbean.testpike.aiservice.ocr.api.dto.OcrGridCell;
import com.sixbean.testpike.aiservice.ocr.api.dto.OcrTextLine;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L2 contract tests for the shared OCR grid assembler .
 *
 * <p>The geometry constants and fixtures are lifted from the DevTools panel
 * parser's golden set so the extraction is provably a
 * zero-drift move: clustering, column ownership (start-inside with edge
 * slack, otherwise width-overlap ratio) and gap-adjacent fragment merging
 * must behave exactly as the old in-body implementation before any consumer
 * is switched over (R40 single source — the fallback image reader and the
 * dialogue grid-read tool are the coming second and third consumers).
 */
class GridAssemblerTest {

    private final GridAssembler assembler = new GridAssembler(0.5);

    /** One OCR cell box: text band between x1..x2 at row top y, height 18px. */
    private static OcrTextLine cell(String text, double x1, double x2, double y) {
        return cell(text, x1, x2, y, false);
    }

    private static OcrTextLine cell(String text, double x1, double x2, double y, boolean annotated) {
        return new OcrTextLine(text, 0.99, List.of(
                List.of(x1, y), List.of(x2, y), List.of(x2, y + 18), List.of(x1, y + 18)), annotated);
    }

    @Test
    void malformedLinesAreDroppedNotGuessed() {
        List<OcrTextLine> lines = Arrays.asList(
                new OcrTextLine(" ", 0.9, List.of(List.of(0.0, 0.0), List.of(10.0, 10.0)), false),
                new OcrTextLine("token", 0.9, List.of(), false),
                null,
                cell("ok", 400, 460, 145));
        List<List<OcrGridCell>> rows = assembler.clusterRows(lines);
        assertEquals(1, rows.size());
        assertEquals(1, rows.get(0).size());
        assertEquals("ok", rows.get(0).get(0).text());
    }

    @Test
    void rowsClusterByVerticalOverlapAndSortLeftToRight() {
        List<List<OcrGridCell>> rows = assembler.clusterRows(List.of(
                cell("Values", 580, 1100, 120),
                cell("Names", 400, 560, 120),
                cell("token", 400, 460, 145),
                cell("eyJhbG...VCJ9", 580, 1090, 145)));
        assertEquals(2, rows.size());
        assertEquals(List.of("Names", "Values"),
                rows.get(0).stream().map(c -> c.text()).toList());
        assertEquals(List.of("token", "eyJhbG...VCJ9"),
                rows.get(1).stream().map(c -> c.text()).toList());
    }

    @Test
    void separateRowBandsNeverMerge() {
        // 25px pitch with 18px glyph boxes: zero vertical overlap, two bands.
        List<List<OcrGridCell>> rows = assembler.clusterRows(List.of(
                cell("lang", 400, 440, 170),
                cell("zh-CN", 580, 640, 195)));
        assertEquals(2, rows.size());
    }

    @Test
    void cellsStartingInsideTheBandOwnItEvenWhenFarWider() {
        // Golden shape from the live-window fixture: a 79px "Values" caption
        // versus a 422px token box — own-width overlap scored 0.18 and the
        // old rule dropped the row; start-inside must keep it.
        OcrGridCell column = new OcrGridCell("Values", 580, 659, 120, 138, false);
        List<OcrGridCell> row = List.of(
                new OcrGridCell("auth_token", 400, 478, 145, 163, false),
                new OcrGridCell("0f1e2d3c4b5a69788796a5b4c3d2e1f0", 580, 1002, 145, 163, true));
        List<OcrGridCell> owned = assembler.cellsBelongingTo(row, column);
        assertEquals(1, owned.size());
        assertEquals("0f1e2d3c4b5a69788796a5b4c3d2e1f0", owned.get(0).text());
        assertTrue(owned.get(0).annotated(), "the red mark survives column assignment");
    }

    @Test
    void cellEndingPastTheBandLeftEdgeButStartingBeforeItIsNotOwned() {
        OcrGridCell narrowValues = new OcrGridCell("Values", 580, 659, 120, 138, false);
        List<OcrGridCell> row = List.of(new OcrGridCell("auth_token", 400, 600, 145, 163, false));
        assertTrue(assembler.cellsBelongingTo(row, narrowValues).isEmpty(),
                "a name cell must not leak into the values column");
    }

    @Test
    void narrowCellFullyInsideTheBandIsOwnedByWidthOverlap() {
        OcrGridCell column = new OcrGridCell("Values", 580, 1100, 120, 138, false);
        List<OcrGridCell> row = List.of(new OcrGridCell("zh-CN", 580, 640, 145, 163, false));
        assertEquals(1, assembler.cellsBelongingTo(row, column).size());
    }

    @Test
    void gapAdjacentFragmentsMergeVerbatimAndStopAtTheFirstHole() {
        List<OcrGridCell> row = List.of(
                new OcrGridCell("eyJhbGciOi", 580, 640, 145, 163, false),
                new OcrGridCell("JIUzI1NiIs", 642, 700, 145, 163, false),
                new OcrGridCell("tail-far", 900, 960, 145, 163, false));
        assertEquals("eyJhbGciOiJIUzI1NiIs", assembler.mergeRow(row));
    }

    @Test
    void mergeOfEmptySelectionIsBlankNotNull() {
        assertEquals("", assembler.mergeRow(List.of()));
    }
}
