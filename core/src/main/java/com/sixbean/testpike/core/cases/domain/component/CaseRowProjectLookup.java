package com.sixbean.testpike.core.cases.domain.component;

import com.sixbean.testpike.core.apiaccess.domain.component.RowIdValues;
import com.sixbean.testpike.core.apiaccess.domain.component.RowOwnershipReads;
import com.sixbean.testpike.core.apiaccess.domain.component.RowProjectLookup;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Case family bridge from a governed read row to the project that owns it.
 *
 * <p>The case detail path already answers "which project does this case belong to" (the H1
 * isolation column ships on every read), so the bridge delegates to it instead of opening a
 * second ownership query; the shared bridge read folds not-found into an unresolvable row and
 * lets every other service problem through untouched.
 */
@Component
public class CaseRowProjectLookup implements RowProjectLookup {

    private final CaseService caseService;

    public CaseRowProjectLookup(CaseService caseService) {
        this.caseService = caseService;
    }

    @Override
    public String entity() {
        return "case";
    }

    @Override
    public List<Long> ownerProjectIds(Object rowIdValue) {
        Long caseId = RowIdValues.longOrNull(rowIdValue);
        if (caseId == null) {
            return null;
        }
        return RowOwnershipReads.gatedBy(
                RowOwnershipReads.rowOrAbsent(() -> caseService.get(caseId).projectId()));
    }
}
