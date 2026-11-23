package com.sixbean.testpike.core.project.domain.component;

import com.sixbean.testpike.core.apiaccess.domain.component.RowIdValues;
import com.sixbean.testpike.core.apiaccess.domain.component.RowOwnershipReads;
import com.sixbean.testpike.core.apiaccess.domain.component.RowProjectLookup;
import com.sixbean.testpike.core.project.api.dto.ProjectEnvInfo;
import com.sixbean.testpike.core.project.domain.service.ProjectEnvService;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Env family bridge from a governed read row to the project that owns it.
 *
 * <p>Governed env reads are addressed by the global envId (the deploy-info and per-env reads carry
 * no project path segment), so the bridge delegates to the existing per-envId lookup — the very
 * reverse lookup the admin env pages run before their membership check, never a second ownership
 * query. Environment rows always hang under a project: a row whose project column is empty is a
 * data anomaly and fails closed, unlike the system-level buckets elsewhere "env" never means
 * visible-to-all.
 */
@Component
public class EnvRowProjectLookup implements RowProjectLookup {

    private final ProjectEnvService projectEnvService;

    public EnvRowProjectLookup(ProjectEnvService projectEnvService) {
        this.projectEnvService = projectEnvService;
    }

    @Override
    public String entity() {
        return "env";
    }

    @Override
    public List<Long> ownerProjectIds(Object rowIdValue) {
        Long envId = RowIdValues.longOrNull(rowIdValue);
        if (envId == null) {
            return null;
        }
        ProjectEnvInfo env = RowOwnershipReads.rowOrAbsent(() -> projectEnvService.getByEnvId(envId));
        return env == null ? null : RowOwnershipReads.gatedBy(env.projectId());
    }
}
