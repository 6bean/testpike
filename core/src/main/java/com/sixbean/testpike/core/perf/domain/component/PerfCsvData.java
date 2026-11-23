package com.sixbean.testpike.core.perf.domain.component;

import java.util.List;

/**
 * Parsed CSV payload of one perf dataset file (header columns + data rows).
 * Produced exclusively by {@link PerfCsvParser}; the dataset ledger stores the
 * header names as metadata and keeps the rows only inside the stored object,
 * so this value object is the single shape both preview and validation share.
 *
 * @param headers column names taken from the first CSV line (validated safe
 *                identifiers, usable as {{VAR}} names in scenario steps)
 * @param rows    data rows (each row has exactly headers.size() cells;
 *                cell values keep their unquoted text)
 */
public record PerfCsvData(List<String> headers, List<List<String>> rows) {

    public int columnCount() {
        return headers.size();
    }

    public int rowCount() {
        return rows.size();
    }
}
