package com.sixbean.testpike.core.cluster.domain.service;

import com.sixbean.testpike.core.cluster.api.dto.UpgradePlanCreateInput;
import com.sixbean.testpike.core.cluster.api.dto.UpgradePlanImpactView;
import com.sixbean.testpike.core.cluster.api.dto.UpgradePlanInfo;
import com.sixbean.testpike.core.cluster.api.dto.UpgradePlanStepView;

import java.util.List;


public interface UpgradePlanService {

    
    UpgradePlanInfo create(UpgradePlanCreateInput input);

    
    UpgradePlanImpactView impact(UpgradePlanCreateInput input);

    UpgradePlanInfo get(long planId);

    List<UpgradePlanInfo> listAll();

    
    List<UpgradePlanStepView> steps(long planId);

    /** PREVIEW→RUNNING (CAS; other states reject in plain words). A plan that
     * declared production scope additionally needs a maintenance window and a
     * second-person review while dual review is strict (decision E; the switch
     * off keeps the old behaviour untouched). */
    UpgradePlanInfo start(long planId);

    /**
     * Second-person review confirmation for a PREVIEW plan (decision E surface).
     * The reviewer identity is bridged from the login context, never from a
     * client-supplied field. Self-review (reviewer == operator) is refused —
     * two distinct people are the entire point. Reviewing an already-reviewed or
     * non-PREVIEW plan refuses with the fresh state (CAS single UPDATE).
     */
    UpgradePlanInfo confirmDualReview(long planId, String reviewer);

    /**
     * One-click rollback plan for the platform waves. The rollback target is
     * never guessed: it is the per node x family {@code last_known_good}
     * ledger column (written when an upgrade receipt lands healthy). Families
     * without a recorded good version reject the whole request naming exactly
     * what is missing; rows already on the good version are skipped. The
     * generated plan walks the persisted wave_seq in reverse (core back first,
     * agent group last) — the direction is a strategy flag, the on-disk
     * ordering spectrum stays single.
     */
    UpgradePlanInfo createRollbackPlan(String operator);

 
    UpgradePlanInfo pause(long planId);

    
    UpgradePlanInfo resume(long planId);

 
    UpgradePlanInfo abort(long planId);

 
    UpgradePlanInfo complete(long planId);

    
    UpgradePlanInfo brake(long planId, String reason);
}
