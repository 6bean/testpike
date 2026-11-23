package com.sixbean.testpike.aiservice.ocr.domain.service;

import com.sixbean.testpike.aiservice.ocr.api.dto.OcrGridCell;
import com.sixbean.testpike.aiservice.ocr.api.dto.OcrTextLine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared grid assembler for OCR text lines : vertical row
 * clustering, column ownership and gap-adjacent fragment merging, lifted
 * verbatim from the DevTools panel parser so every screenshot-grid consumer
 * has one implementation (R40). The panel header/region semantics and the
 * coming fallback/tool consumers stay on top of this component — never
 * duplicated inside it.
 */
@Service
public class GridAssembler {

    /** Adjacent OCR fragments closer than this many pixels are one logical cell. */
    private static final double MERGE_GAP_PX = 6.0;

    /**
     * Left-edge slack for column ownership. Header caption boxes are only as
     * wide as their text while cell boxes carry pixel jitter, so a few pixels
     * before the band still counts as starting inside it.
     */
    private static final double COLUMN_EDGE_TOL_PX = 8.0;

    /** A cell outside the "starts inside" rule owns the column at this width-overlap ratio. */
    private final double columnOverlapRatio;

    public GridAssembler(
            @Value("${testpike.devtools.column-overlap-ratio:0.5}") double columnOverlapRatio) {
        this.columnOverlapRatio = columnOverlapRatio;
    }

    /**
     * Greedy vertical clustering: table rows have uniform height, so a cell
     * joins the open row when at least half of its height overlaps the row
     * band. Cells are emitted top-to-bottom, left-to-right; malformed lines
     * are dropped.
     */
    public List<List<OcrGridCell>> clusterRows(List<OcrTextLine> lines) {
        List<OcrGridCell> cells = new ArrayList<>();
        for (OcrTextLine line : lines == null ? List.<OcrTextLine>of() : lines) {
            OcrGridCell cell = OcrGridCell.of(line);
            if (cell != null) {
                cells.add(cell);
            }
        }
        cells.sort((a, b) -> a.yMin() != b.yMin()
                ? Double.compare(a.yMin(), b.yMin())
                : Double.compare(a.xMin(), b.xMin()));
        List<List<OcrGridCell>> rows = new ArrayList<>();
        for (OcrGridCell cell : cells) {
            List<OcrGridCell> open = rows.isEmpty() ? null : rows.get(rows.size() - 1);
            if (open != null) {
                double bandYMin = open.stream().mapToDouble(OcrGridCell::yMin).min().orElseThrow();
                double bandYMax = open.stream().mapToDouble(OcrGridCell::yMax).max().orElseThrow();
                if (cell.heightOverlapRatio(bandYMin, bandYMax) >= 0.5) {
                    open.add(cell);
                    continue;
                }
            }
            List<OcrGridCell> row = new ArrayList<>();
            row.add(cell);
            rows.add(row);
        }
        return rows;
    }

    /**
     * Column ownership for the cells of one row. A cell belongs when it
     * starts inside the band (DevTools values overflow rightward past narrow
     * captions) or when most of its width still lies within the band — the
     * pre-existing rule, kept because own-width-only scoring dropped every
     * long value box (a 422px token against a 79px caption scored 0.18).
     */
    public List<OcrGridCell> cellsBelongingTo(List<OcrGridCell> row, OcrGridCell column) {
        if (column == null) {
            return List.of();
        }
        return row.stream().filter(cell -> belongsTo(cell, column)).toList();
    }

    /** True when any red-marked cell of the row overlaps the given column interval. */
    public boolean touchesMarkedCell(List<OcrGridCell> row, OcrGridCell column) {
        if (column == null) {
            return false;
        }
        return row.stream().anyMatch(cell -> cell.annotated() && belongsTo(cell, column));
    }

    /**
     * Concatenates the gap-adjacent fragments of one column run, left to
     * right, stopping at the first hole wider than the merge gap. Returns
     * the trimmed text, or an empty string when nothing was owned.
     */
    public String mergeRow(List<OcrGridCell> cells) {
        List<OcrGridCell> ordered = cells.stream()
                .sorted((a, b) -> Double.compare(a.xMin(), b.xMin()))
                .toList();
        String merged = null;
        double chainRight = Double.NEGATIVE_INFINITY;
        for (OcrGridCell cell : ordered) {
            if (merged == null) {
                merged = cell.text();
                chainRight = cell.xMax();
            } else if (cell.xMin() - chainRight <= MERGE_GAP_PX) {
                merged = merged + cell.text();
                chainRight = cell.xMax();
            } else {
                break;
            }
        }
        return merged == null || merged.isBlank() ? "" : merged.trim();
    }

    /**
 * Split one clustered row into its column runs :
 * cells closer than the merge gap chain left to right into one run, a
 * wider hole starts the next run — the same adjacency rule as
 * {@link #mergeRow}, exposed per row so table assembly never re-implements
 * clustering (R40).
 */
    public List<String> rowCells(List<OcrGridCell> row) {
        List<String> runs = new ArrayList<>();
        if (row == null || row.isEmpty()) {
            return runs;
        }
        List<OcrGridCell> ordered = row.stream()
                .sorted((a, b) -> Double.compare(a.xMin(), b.xMin()))
                .toList();
        StringBuilder chain = new StringBuilder(ordered.get(0).text());
        double chainRight = ordered.get(0).xMax();
        for (OcrGridCell cell : ordered.subList(1, ordered.size())) {
            if (cell.xMin() - chainRight <= MERGE_GAP_PX) {
                chain.append(cell.text());
                chainRight = cell.xMax();
            } else {
                runs.add(chain.toString().trim());
                chain = new StringBuilder(cell.text());
                chainRight = cell.xMax();
            }
        }
        runs.add(chain.toString().trim());
        runs.removeIf(String::isBlank);
        return runs;
    }

    private boolean belongsTo(OcrGridCell cell, OcrGridCell column) {
        boolean startsInside = cell.xMin() >= column.xMin() - COLUMN_EDGE_TOL_PX
                && cell.xMin() < column.xMax();
        return startsInside || cell.widthOverlapRatio(column) >= columnOverlapRatio;
    }
}
