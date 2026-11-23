package com.sixbean.testpike.core.session.domain.service;

import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.session.api.dto.LoginStateInfo;
import com.sixbean.testpike.core.session.api.dto.LoginStateInput;

import java.util.List;

/**
 * Login-state ledger service (AV2 storage & inventory).
 *
 * <p>Renewal semantics (D9): registering an existing (project, state_name)
 * updates that row in place — a login state is consumable, so re-capture is an
 * update, never an append. Different accounts get different state names.
 * Secret material is encrypted into tp_credential (single vault) and referenced
 * by credential_id; this service never reads or returns plaintext.
 */
public interface LoginStateService {

    /** Register or renew one state; returns the ledger row id. */
    long register(long projectId, LoginStateInput input);

    /**
     * Edit one ledger row in place by id (ledger dialog save). Unlike
     * {@link #register} this door addresses the row directly and keeps the
     * lifecycle columns honest: a blank secret never touches the vault,
     * the four-state verification stamp is only dropped when the replay
     * material actually changed (kind, reference key, secret or the
     * combined-header list), and a disabled row stays disabled. Renaming is
     * allowed but must not collide with another row of the same project —
     * the state name is the anchor consumers reference, so an ambiguous
     * rename is refused by name. An unchanged save is a true no-op (no
     * timestamp churn), same idempotency rule as the other set editors.
     */
    void update(long id, LoginStateInput input);

    /** One ledger row by id (404 honest when absent). */
    LoginStateInfo get(long id);

    /** Project segment (enabledOnly=TRUE keeps only usable rows, null = all). */
    List<LoginStateInfo> list(long projectId, Boolean enabledOnly);

    /**
 * Paged ledger segment for the management door (AV2 / S-group display-scale
 * rule: an admin list door is bounded and reports the true total).
 *
 * @param keyword case-insensitive match on state name or account label, blank = all
 * @param status 1 = enabled, 0 = disabled, null = all
 * @param sortKey a ledger column name from the server-side whitelist; a
 * derived key such as the four-state light is refused,
 * because sorting only the loaded pageful would be a fake
 * global order
 * @param sortAsc false for descending, null/true for ascending
 * @param page 1-based page number
 * @param size rows per page, capped by {@code PageQueryPolicy} (over-cap is rejected)
 */
    PageResult<LoginStateInfo> listPage(long projectId, String keyword, Integer status,
                                        String sortKey, Boolean sortAsc, int page, int size);

    /** Enable/disable (disable takes effect from the next session, running sessions are not cut). */
    void setEnabled(long id, boolean enabled);

    /**
     * Verification write-back (AV3 trigger: replay check / 401-403 hit).
     * A failure marks the row invalid so the light and precheck react;
     * success only refreshes the timestamp.
     */
    void markVerified(long id, boolean valid);

    /**
     * Verification write-back with a plain-language reason (AV2 four-light state
     * "invalid (reason)"). A non-blank reason lands in the row's remark field;
     * null/blank keeps the previous behaviour of leaving remark untouched.
     * This stays the single write path — callers must not copy the UPDATE.
     */
    void markVerified(long id, boolean valid, String reason);

    /** Remove the ledger row (the vault credential row stays for audit trail). */
    void delete(long id);
}
