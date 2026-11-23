package com.sixbean.testpike.aiservice.ocr.api.dto;

import java.util.List;

/**
 * One usable OCR cell reduced to its axis-aligned bounds (BF batch :
 * lifted out of the DevTools panel parser so every grid consumer — the
 * panel aligner, the vision-less image fallback and the dialogue grid-read
 * tool — shares one geometry model, R40 single source).
 *
 * <p>Lines with a blank text or a malformed bounding box yield {@code null}
 * from {@link #of} so callers drop them instead of guessing geometry.
 */
public record OcrGridCell(String text, double xMin, double xMax, double yMin, double yMax,
                          boolean annotated) {

    public static OcrGridCell of(OcrTextLine line) {
        if (line == null || line.text() == null || line.text().isBlank()
                || line.box() == null || line.box().isEmpty()) {
            return null;
        }
        double xMin = Double.MAX_VALUE;
        double xMax = -Double.MAX_VALUE;
        double yMin = Double.MAX_VALUE;
        double yMax = -Double.MAX_VALUE;
        for (List<Double> point : line.box()) {
            if (point == null || point.size() < 2 || point.get(0) == null || point.get(1) == null) {
                return null;
            }
            double x = point.get(0);
            double y = point.get(1);
            xMin = Math.min(xMin, x);
            xMax = Math.max(xMax, x);
            yMin = Math.min(yMin, y);
            yMax = Math.max(yMax, y);
        }
        if (xMax - xMin <= 0 || yMax - yMin <= 0) {
            return null;
        }
        return new OcrGridCell(line.text().trim(), xMin, xMax, yMin, yMax, line.annotated());
    }

    public double width() {
        return xMax - xMin;
    }

    public double height() {
        return yMax - yMin;
    }

    /** Share of this cell's width that falls inside the given column interval. */
    public double widthOverlapRatio(OcrGridCell column) {
        double overlap = Math.min(xMax, column.xMax) - Math.max(xMin, column.xMin);
        return overlap <= 0 ? 0 : overlap / width();
    }

    /** Vertical band overlap with a row band, measured against this cell's height. */
    public double heightOverlapRatio(double bandYMin, double bandYMax) {
        double overlap = Math.min(yMax, bandYMax) - Math.max(yMin, bandYMin);
        return overlap <= 0 ? 0 : overlap / height();
    }
}
