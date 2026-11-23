package com.sixbean.testpike.admin.aichat.app.service;

import com.sixbean.testpike.admin.aichat.api.dto.EvolutionTrendMirrorView;
import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;

/**
 * Admin application service behind the evolution trend report door (AF-i
 * phase 4): reads the core before/after report after the permission gate.
 */
public interface EvolutionTrendAdminService {

    /**
 * Before/after trend report for one run window.
 *
 * @param month yyyy-MM run window key (shape validated by core, fail-closed)
 * @param projectId null = platform-wide report (ADMIN only), non-null = that
 * project's segment (its manager) — the same posture the
 * signals door uses, no new spectrum
 */
    EvolutionTrendMirrorView trend(CurrentUser user, String month, Long projectId);
}
