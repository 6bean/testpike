package com.sixbean.testpike.core.mock.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.mock.api.dto.MockStubInfo;
import com.sixbean.testpike.core.mock.api.dto.SpecUpgradePlanView;
import com.sixbean.testpike.core.mock.api.dto.SpecUpgradeRow;
import com.sixbean.testpike.core.mock.domain.component.SpecUpgradePlan;
import com.sixbean.testpike.core.mock.domain.service.MockSpecUpgradeService;
import com.sixbean.testpike.core.mock.domain.service.MockStubService;
import com.sixbean.testpike.core.mock.domain.service.SpecImportVerdictService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Internal doors of the contract-upgrade difference surface (MJ P6/T717b):
 * the admin wizard mirror consumes the three-state plan (added rows joined
 * with the single advice source, changed/removed rows naming the exact rule)
 * and the stale-marking step through exactly these two reads.
 * <p>Thin bridge — planning, advice and marking semantics all live in the
 * services (single sources: SpecDiffOps for the drift, SpecImportVerdictService
 * for the advice floor, MockSpecUpgradeService for the mark). Nothing written
 * here bypasses the human confirm gate: marking only disables + flags rows
 * the plan already named, and the hit ledger stays untouched.
 */
@RestController
@RequestMapping("/internal/mock")
public class MockSpecUpgradeController {

    private final MockSpecUpgradeService upgradeService;
    private final SpecImportVerdictService verdictService;
    private final MockStubService stubService;

    public MockSpecUpgradeController(MockSpecUpgradeService upgradeService,
                                     SpecImportVerdictService verdictService,
                                     MockStubService stubService) {
        this.upgradeService = upgradeService;
        this.verdictService = verdictService;
        this.stubService = stubService;
    }

    /** One-frame three-state upgrade plan for a space against one contract version. */
    @GetMapping("/spec-upgrade/plan")
    public Response<SpecUpgradePlanView> plan(@RequestParam long spaceId,
                                              @RequestParam long specId) {
        SpecUpgradePlan plan = upgradeService.planDiff(spaceId, specId);
        List<SpecUpgradeRow> changed = rowsOf(plan.changedStubIds());
        List<SpecUpgradeRow> removed = rowsOf(plan.removedStubIds());
        return Response.builderSuccess(new SpecUpgradePlanView(
                verdictService.advise(specId, spaceId, plan.addedDrafts()),
                changed, removed, plan.hasPrevious()));
    }

    /**
     * Disable and stale-flag the rules whose endpoint left the contract.
     * Returns the honest count of rows newly marked (replay files zero).
     */
    @PostMapping("/spec-upgrade/mark-removed")
    public Response<Integer> markRemoved(@RequestParam long spaceId,
                                         @RequestParam long specId) {
        return Response.builderSuccess(upgradeService.markRemovedStale(spaceId, specId));
    }

    private List<SpecUpgradeRow> rowsOf(List<Long> stubIds) {
        return stubIds.stream().map(stubService::get).map(SpecUpgradeRow::from).toList();
    }
}
