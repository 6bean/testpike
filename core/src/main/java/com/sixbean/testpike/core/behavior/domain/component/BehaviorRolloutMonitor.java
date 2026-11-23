package com.sixbean.testpike.core.behavior.domain.component;

import com.sixbean.testpike.common.exception.ValidationException;
import com.sixbean.testpike.core.behavior.api.dto.BehaviorOutcomeAdvisory;
import com.sixbean.testpike.core.behavior.api.dto.BehaviorRolloutView;
import com.sixbean.testpike.core.behavior.domain.service.BehaviorOutcomeService;
import com.sixbean.testpike.core.behavior.domain.service.impl.BehaviorOutcomeServiceImpl;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.prompt.api.dto.PromptTemplateItem;
import com.sixbean.testpike.core.prompt.domain.component.PromptTemplateStatus;
import com.sixbean.testpike.core.prompt.domain.service.PromptTemplateService;
import org.springframework.stereotype.Component;

/**
 * D4 gray-release pairing over the outcome ledger .
 * The ledger counts verdicts per template version; this monitor gives the
 * gray channel its eyes: it resolves the live gray row of a family, picks the
 * control (current full release, else the previous gray row) and asks the
 * outcome service for the degradation advisory. Read-only by design — the
 * advisory never promotes, rolls back or rewrites any template row (red line:
 * write gates stay a human action, K8 tolerance hot-reloads per call).
 */
@Component
public class BehaviorRolloutMonitor {

    private final PromptTemplateService templates;
    private final BehaviorOutcomeService outcomes;
    private final ConfigService configService;

    public BehaviorRolloutMonitor(PromptTemplateService templates,
                                  BehaviorOutcomeService outcomes,
                                  ConfigService configService) {
        this.templates = templates;
        this.outcomes = outcomes;
        this.configService = configService;
    }

    /**
     * Rollout view for one template family, or NULL when it has no live gray
     * release. A gray row without enough ledger data still returns a view:
     * silence here would be indistinguishable from "nothing deployed", so the
     * incomplete comparison is reported with the missing side named in the note.
     */
    public BehaviorRolloutView advisoryFor(String domain, String name) {
        if (domain == null || domain.isBlank() || name == null || name.isBlank()) {
            throw new ValidationException("template domain and name must not be blank for a rollout review");
        }
        String d = domain.trim();
        String n = name.trim();
        PromptTemplateItem gray = templates.latestByStatus(n, d, PromptTemplateStatus.GRAY);
        if (gray == null || gray.id() == null) {
            return null;
        }
        PromptTemplateItem control = templates.latestByStatus(n, d, PromptTemplateStatus.FULL);
        if (control == null || control.id() == null) {
            control = previousGray(n, d, gray.version());
        }
        if (control == null || control.id() == null) {
            return new BehaviorRolloutView(d, n, gray.id(), null, incomplete(d, gray.id()));
        }
        BehaviorOutcomeAdvisory advisory = outcomes.compareVersions(d, control.id(), gray.id());
        return new BehaviorRolloutView(d, n, gray.id(), control.id(), advisory);
    }

    /** Older gray row of the same family (versions before the live one, highest first). */
    private PromptTemplateItem previousGray(String name, String domain, Integer liveVersion) {
        if (liveVersion == null) {
            return null;
        }
        return templates.latestGrayBefore(name, domain, liveVersion);
    }

    /** Advisory shape for "cannot compare yet": no verdict, tolerance shown, reason named. */
    private BehaviorOutcomeAdvisory incomplete(String domain, long candidateId) {
        int tolerance = BehaviorOutcomeServiceImpl.parseThreshold(
                configService.getValue(BehaviorOutcomeServiceImpl.KEY_AB_THRESHOLD,
                        String.valueOf(BehaviorOutcomeServiceImpl.SHIPPED_DEFAULT_PP)));
        return new BehaviorOutcomeAdvisory(domain, null, candidateId, null, null, null, tolerance, false,
                "Comparison skipped: no outcome rows on either side yet (this family has no full "
                        + "release and no earlier gray to act as control) — an advisory needs data on both sides.");
    }
}
