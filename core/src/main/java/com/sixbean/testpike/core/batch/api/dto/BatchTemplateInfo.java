package com.sixbean.testpike.core.batch.api.dto;

import java.util.List;

/**
 * Batch template row definition for tp_batch_template.
 *
 * @param id template ID.
 * @param projectId owning project (cross-project reuse is rejected).
 * @param name template name.
 * @param caseIds member snapshot (soft-deleted members are excluded when reused).
 * @param envPolicy environment policy placeholder (null follows the selected environment).
 * @param createdBy creator (management permission is granted to the creator or owner).
 * @param createdAt creation time.
 * @param cronExpr cron expression for scheduled reuse (null means no schedule is attached).
 * @param enabled 1 enables the schedule and 0 disables it (the migration default is 0, so existing templates have no behavior change).
 * @param scheduleEnvId target environment for scheduled reuse (required when enabled and pinned in advance for unattended execution).
 * @param cleanAppData template clean-app-data default flag (migration: persisted to batch rows when batches are created from the template; the default value is not a forced override).
 */
public record BatchTemplateInfo(long id, long projectId, String name, List<Long> caseIds,
                                String envPolicy, Long createdBy, String createdAt,
                                String cronExpr, int enabled, Long scheduleEnvId,
                                int cleanAppData) {

    /** Legacy 10-parameter compatibility constructor (the clean-app-data default flag is 0/off, so existing callers require no changes). */
    public BatchTemplateInfo(long id, long projectId, String name, List<Long> caseIds,
                             String envPolicy, Long createdBy, String createdAt,
                             String cronExpr, int enabled, Long scheduleEnvId) {
        this(id, projectId, name, caseIds, envPolicy, createdBy, createdAt, cronExpr, enabled,
                scheduleEnvId, 0);
    }
}
