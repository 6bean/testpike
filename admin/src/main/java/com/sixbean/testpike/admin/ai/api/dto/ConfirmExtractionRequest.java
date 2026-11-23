package com.sixbean.testpike.admin.ai.api.dto;

import java.util.List;
import java.util.Map;

/**
 *
 * @param dispositions per-item tri-state resolution keyed by preview item id
 * (KEEP_AS_IS closes without a card, FORCE_NEW lands with an override
 * audit; null/unknown keeps the default update-proposal card path)
 */
public record ConfirmExtractionRequest(List<ExtractionItemView> items,
                                       Map<Long, String> dispositions) {

    /** Legacy call sites without dispositions keep compiling (default card path). */
    public ConfirmExtractionRequest(List<ExtractionItemView> items) {
        this(items, null);
    }
}
