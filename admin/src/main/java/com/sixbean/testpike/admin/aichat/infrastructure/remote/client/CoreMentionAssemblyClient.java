package com.sixbean.testpike.admin.aichat.infrastructure.remote.client;

import java.util.List;

/**
 * Core assembly doors for the dialog @-mention palette (AO batch
 * stage 1). The palette candidates ARE the assembly sets the run consumes —
 * one source, one gate verdict: published/enabled filtering, the five-way
 * tool gate and the R34 system-level pass-through all stay single-sourced
 * core-side; admin only projects the (kind,id,title) label shape.
 */
public interface CoreMentionAssemblyClient {

    /**
     * SKILL assembly candidates of one project (priority order as core
     * returns them; header-less read door like the S5 consumption seam).
     */
    List<MentionSkillRow> listSkillAssembly(long projectId);

    /**
     * FC assembly candidates the ACTOR can actually call (X-Operator-Id
     * drives core's five-way gate — without the header the palette would
     * show tools the subsequent run must refuse).
     */
    List<MentionToolRow> listToolAssembly(long projectId, long actorId);
}
