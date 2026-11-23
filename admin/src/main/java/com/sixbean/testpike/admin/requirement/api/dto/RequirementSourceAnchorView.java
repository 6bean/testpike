package com.sixbean.testpike.admin.requirement.api.dto;

import java.util.List;

/**
 * Mirror of the core evidence anchor stored on a requirement row (design BA
 * 3.2, exposed for the list surfaces by the view-original segment).
 *
 * <p>Field names match the core record one-for-one so the Feign payload
 * deserialises by name without a mapping layer. A null anchor means the point
 * has no recorded source: the caller then shows an honest disabled state
 * instead of a dead link.</p>
 *
 * @param documentId source document id, null when the point was not adopted
 * from a document extraction
 * @param chunkIds source chunk row ids (empty when the excerpt could not be
 * attributed to a chunk)
 * @param evidence verbatim supporting excerpt captured at adoption time
 */
public record RequirementSourceAnchorView(Long documentId, List<Long> chunkIds, String evidence) {
}
