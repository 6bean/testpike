package com.sixbean.testpike.admin.webelement.api.dto;

import java.util.List;

/**
 * Admin mirror of the core bounded referencing-case page. Carries the
 * resolved element name and owning project id so the bridge can guard
 * membership in one round trip; an unknown element answers an empty page
 * with null fields.
 */
public record ReferencingCasePage(
        List<ReferencingCaseView> items,
        long total,
        int page,
        int pageSize,
        String elementName,
        Long projectId) {
}
