package com.sixbean.testpike.aiservice.devtoolspanel.domain.service.impl;

import com.sixbean.testpike.aiservice.devtoolspanel.api.dto.DevToolsPanelParseResult;
import com.sixbean.testpike.aiservice.devtoolspanel.api.dto.LoginStateCandidate;
import com.sixbean.testpike.aiservice.devtoolspanel.domain.service.DevToolsPanelParseService;
import com.sixbean.testpike.aiservice.ocr.api.dto.OcrGridCell;
import com.sixbean.testpike.aiservice.ocr.api.dto.OcrTextLine;
import com.sixbean.testpike.aiservice.ocr.domain.service.GridAssembler;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Coordinate-driven grid aligner for DevTools Application panel screenshots
 * . OCR lines are clustered into table rows by vertical band
 * overlap, a header band ("Names"/"Values" for Local Storage, "Name"+"Value"
 * plus any of Domain/Path/Expires/Size/HttpOnly/SameSite for Cookies) fixes
 * the column intervals, and every later row is split into columns by
 * horizontal overlap. Values that end with an ellipsis signature are
 * DevTools column-width truncation, so those rows are parked in
 * {@code truncatedSuspects} (fail closed, never offered as clean candidates).
 *
 * <p>Multiple header bands in one pass are supported (stacked/stitched
 * screenshots): each header row switches the active region. Cells outside
 * every column interval (the left storage tree, tab captions) are dropped.
 *
 * <p>BF batch : the geometry layer (row clustering, column ownership,
 * fragment merging) is single-sourced in {@link GridAssembler}; only the
 * DevTools-specific header/region semantics live here.
 */
@Service
public class DevToolsPanelParseServiceImpl implements DevToolsPanelParseService {

    private static final String KIND_COOKIE = "cookie";
    private static final String KIND_LOCAL_STORAGE = "local_storage";
    private static final Pattern ORIGIN_URL = Pattern.compile("^https?://\\S+$");
    private static final Set<String> COOKIE_SIGNATURE_COLUMNS =
            Set.of("domain", "path", "expires", "size", "httponly", "samesite");
    private static final Set<String> HTTP_ONLY_MARKS = Set.of("✓", "✔", "x", "true");

    private final GridAssembler gridAssembler;

    public DevToolsPanelParseServiceImpl(GridAssembler gridAssembler) {
        this.gridAssembler = gridAssembler;
    }

    @Override
    public DevToolsPanelParseResult parse(List<OcrTextLine> lines) {
        List<LoginStateCandidate> candidates = new ArrayList<>();
        List<LoginStateCandidate> suspects = new ArrayList<>();
        String regionKind = null;
        Map<String, OcrGridCell> columns = Map.of();
        String pendingOrigin = null;
        String regionOrigin = null;

        for (List<OcrGridCell> row : gridAssembler.clusterRows(lines)) {
            Map<String, OcrGridCell> headerIndex = indexByLowerText(row);
            if (headerIndex.containsKey("names") && headerIndex.containsKey("values")) {
                regionKind = KIND_LOCAL_STORAGE;
                columns = headerIndex;
                regionOrigin = pendingOrigin;
                continue;
            }
            if (headerIndex.containsKey("name") && headerIndex.containsKey("value")
                    && COOKIE_SIGNATURE_COLUMNS.stream().anyMatch(headerIndex::containsKey)) {
                regionKind = KIND_COOKIE;
                columns = headerIndex;
                continue;
            }
            if (regionKind == null) {
                pendingOrigin = lastOriginUrl(row, pendingOrigin);
                continue;
            }
            readDataRow(regionKind, columns, row, regionOrigin, candidates, suspects);
        }
        return new DevToolsPanelParseResult(candidates, suspects);
    }

    private void readDataRow(String regionKind, Map<String, OcrGridCell> columns,
                             List<OcrGridCell> row, String regionOrigin,
                             List<LoginStateCandidate> candidates,
                             List<LoginStateCandidate> suspects) {
        OcrGridCell nameColumn = columns.get(KIND_LOCAL_STORAGE.equals(regionKind) ? "names" : "name");
        OcrGridCell valueColumn = columns.get(KIND_LOCAL_STORAGE.equals(regionKind) ? "values" : "value");
        String name = columnText(nameColumn, row);
        String value = columnText(valueColumn, row);
        if (name == null || value == null) {
            return;
        }
        String origin;
        String expiresAt = null;
        boolean httpOnly = false;
        if (KIND_COOKIE.equals(regionKind)) {
            origin = columnText(columns.get("domain"), row);
            expiresAt = columnText(columns.get("expires"), row);
            String mark = columnText(columns.get("httponly"), row);
            httpOnly = mark != null && HTTP_ONLY_MARKS.contains(mark.toLowerCase(Locale.ROOT));
        } else {
            origin = regionOrigin;
        }
        boolean annotated = gridAssembler.touchesMarkedCell(row, nameColumn)
                || gridAssembler.touchesMarkedCell(row, valueColumn);
        LoginStateCandidate candidate =
                new LoginStateCandidate(regionKind, name, value, origin, expiresAt, httpOnly, annotated);
        // A red mark never overrides the truncation fail-closed split: a marked
        // but truncated row still lands in suspects, carrying its flag for the
        // retake hint.
        if (looksTruncated(name) || looksTruncated(value)) {
            suspects.add(candidate);
        } else {
            candidates.add(candidate);
        }
    }

    /** Text of one column in this row: owned cells merged gap-adjacent, null when empty. */
    private String columnText(OcrGridCell column, List<OcrGridCell> row) {
        String merged = gridAssembler.mergeRow(gridAssembler.cellsBelongingTo(row, column));
        return merged.isEmpty() ? null : merged;
    }

    private boolean looksTruncated(String text) {
        return text.endsWith("…") || text.endsWith("...");
    }

    private String lastOriginUrl(List<OcrGridCell> row, String current) {
        String latest = current;
        for (OcrGridCell cell : row) {
            if (ORIGIN_URL.matcher(cell.text()).matches()) {
                latest = cell.text();
            }
        }
        return latest;
    }

    private Map<String, OcrGridCell> indexByLowerText(List<OcrGridCell> row) {
        Map<String, OcrGridCell> index = new LinkedHashMap<>();
        for (OcrGridCell cell : row) {
            index.putIfAbsent(cell.text().toLowerCase(Locale.ROOT), cell);
        }
        return index;
    }
}
