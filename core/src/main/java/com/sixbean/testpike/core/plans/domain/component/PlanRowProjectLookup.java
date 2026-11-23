package com.sixbean.testpike.core.plans.domain.component;

import com.sixbean.testpike.core.apiaccess.domain.component.RowIdValues;
import com.sixbean.testpike.core.apiaccess.domain.component.RowOwnershipReads;
import com.sixbean.testpike.core.apiaccess.domain.component.RowProjectLookup;
import com.sixbean.testpike.core.plans.domain.service.PlanService;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Plan family bridge from a governed read row to the project that owns it.
 *
 * <p>The plan detail path already answers "which project does this plan belong to" (the isolation
 * column ships on every read), so the bridge delegates to it instead of opening a second
 * ownership query; the shared bridge read folds not-found into an unresolvable row and lets
 * every other service problem through untouched.
 */
@Component
public class PlanRowProjectLookup implements RowProjectLookup {

    private final PlanService planService;

    public PlanRowProjectLookup(PlanService planService) {
        this.planService = planService;
    }

    @Override
    public String entity() {
        return "plan";
    }

    @Override
    public List<Long> ownerProjectIds(Object rowIdValue) {
        Long planId = RowIdValues.longOrNull(rowIdValue);
        if (planId == null) {
            return null;
        }
        return RowOwnershipReads.gatedBy(
                RowOwnershipReads.rowOrAbsent(() -> planService.get(planId).projectId()));
    }
}
