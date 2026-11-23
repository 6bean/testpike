package com.sixbean.testpike.core.webelement.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.webelement.api.dto.ReferencingCasePage;
import com.sixbean.testpike.core.webelement.domain.component.ElementReferencingCaseQuery;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Referencing-case read door (MCP tail package 1, element drawer reference
 * list). The anchor ledger written by stage1 / self-heal / manual anchors is
 * the only index — this door reads it, it never maintains a second one.
 * Membership guarding stays on the admin side; the resolved owning project
 * rides on the page so the bridge needs exactly one round trip.
 */
@RestController
@RequestMapping("/internal/web-elements")
public class WebElementReferencingCasesInternalController {

    private final ElementReferencingCaseQuery referencingCasesQuery;

    public WebElementReferencingCasesInternalController(
            ElementReferencingCaseQuery referencingCasesQuery) {
        this.referencingCasesQuery = referencingCasesQuery;
    }

    /** Bounded page of case steps that reference one ledger element. */
    @GetMapping("/{id}/referencing-cases")
    public Response<ReferencingCasePage> referencingCases(
            @PathVariable long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        return Response.builderSuccess(referencingCasesQuery.byElement(id, page, pageSize));
    }
}
