package com.sixbean.testpike.admin.apitest.api.dto;

import java.util.List;

/**
 * Effective-defaults preview view (admin face; mirrors the core
 * ApiEffectivePreview wire shape).
 *
 * @param authSource four-valued verdict: request / collection / none-explicit / none
 */
public record EffectivePreviewDto(long requestId, long collectionId, String collectionName,
                                  List<EffectiveHeaderDto> headers, String headersJson,
                                  String authJson, String authSource) {
}
