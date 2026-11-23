package com.sixbean.testpike.aiservice.ocr.api.dto;

/**
 * Shape verdict of the dialog OCR grid door (ocr.grid_read). Closed enum on
 * purpose — the payload shape the model sees never drifts per screenshot.
 */
public enum OcrDialogGridForm {

    /** At least two rows sharing one identical column count: a regular matrix, {@code markdown} carries the GFM table. */
    TABLE,

    /** No multi-column structure: the picture is prose lines, consumers read {@code rows} one cell at a time. */
    TEXT,

    /** Multi-column but ragged (rows disagree on column count): raw row matrix in {@code rows}, no markdown claim. */
    GRID
}
