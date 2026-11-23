package com.sixbean.testpike.core.skill.domain.service;

import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.skill.api.dto.ProjectMemoryCreateInput;
import com.sixbean.testpike.core.skill.api.dto.ProjectMemoryInfo;

/**
 * Project-memory ledger governance (AP batch ; state machine re-ruled by
 * ruling section 8 to four states — pending-confirmation / active /
 * disabled / rejected, plus the recycle bin exit). Distilled entries land as
 * pending (inject_status=2) and become injectable ONLY through the shared
 * draft-confirmation door (DraftConfirmationService + CtxMemoryDraftStrategy);
 * this service owns visibility, the reject exit, disable/enable and recycling.
 * <p>All actions write tp_audit_log (caller-attributed) and take effect on
 * the NEXT assembly read — SkillConsumptionService filters inject_status=1
 * and deleted=0 at the single existing read point, so governance never
 * forks the injection chain.
 */
public interface ProjectMemoryLedgerService {

    /** Ledger rows of one project except recycled ones, newest first; keyword matches content, status filters 0/1/2/3. */
    PageResult<ProjectMemoryInfo> list(Long projectId, String keyword, Integer injectStatus, int page, int size);

    /** Manual fixed-injection entry (source_ref [{type:manual}]), rides the same assembly channel. */
    ProjectMemoryInfo create(Long operatorId, ProjectMemoryCreateInput input);

    /** Disable (0) or re-enable (1) an ACTIVE-family row; pending/rejected rows must go through the confirmation door. Missing / foreign-project / recycled ids fail 40401, bad status 40001. */
    ProjectMemoryInfo setInjectStatus(Long operatorId, Long projectId, long id, int injectStatus);

    /** Reject a distilled entry awaiting confirmation (pending 2 -> rejected 3, audited core-side; never injected, stays visible in the ledger). */
    ProjectMemoryInfo reject(Long operatorId, Long projectId, long id);

    /** Soft-delete into the recycle bin (never erased); excluded from the next assembly read. */
    void delete(Long operatorId, Long projectId, long id);

    /** Recycle-bin rows of one project (restore candidates). */
    PageResult<ProjectMemoryInfo> recycleBin(Long projectId, int page, int size);

    /** Return a recycled entry to the ledger keeping its original inject_status. */
    ProjectMemoryInfo restore(Long operatorId, Long projectId, long id);
}
