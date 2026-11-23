package com.sixbean.testpike.core.webelement.api.dto;

import java.util.List;

/**
 * Bounded page of case steps that reference one ledger element. Carries the
 * resolved element name (the anchor ledger keys references by name, and
 * same-named elements across pages honestly share the name-union list) and
 * the owning project id so the admin bridge can guard membership in one
 * round trip; an unknown element answers an empty page with null fields.
 */
public record ReferencingCasePage(
        List<ReferencingCaseView> items,
        long total,
        int page,
        int pageSize,
        String elementName,
        Long projectId) {
}
