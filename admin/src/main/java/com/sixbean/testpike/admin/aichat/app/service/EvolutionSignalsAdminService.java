package com.sixbean.testpike.admin.aichat.app.service;

import com.sixbean.testpike.admin.aichat.api.dto.EvolutionSignalsMirrorView;
import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;

/**
 * Admin application service behind the self-evolution observation door
 * (AF-i phase 2b): reads the core rollup after the permission gate.
 */
public interface EvolutionSignalsAdminService {

    /**
 * Observation rollup for one month window.
 *
 * @param month yyyy-MM window key (shape validated by core, fail-closed)
 * @param projectId null = platform-wide rollup (ADMIN only), non-null = that
 * project's segment (its manager) — the same posture the
 * cost overview gate uses, no new spectrum
 */
    EvolutionSignalsMirrorView signals(CurrentUser user, String month, Long projectId);
}
