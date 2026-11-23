package com.sixbean.testpike.core.cases.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;

import com.sixbean.testpike.common.exception.ServiceException;

import java.util.Map;

/**
 * Review action (the action enum for POST /cases/{id}/review in the interface design).
 * <p>label is the tp_review_log.action stored value (Chinese, per the database design); event is the corresponding state-machine event.
 *
 */
public enum ReviewAction {

    PASS("pass", "通过", CaseEvent.PASS),
    NEED_CHANGE("need_change", "需修改", CaseEvent.NEED_CHANGE),
    REJECT("reject", "拒绝", CaseEvent.REJECT);

    private static final Map<String, ReviewAction> BY_CODE = Map.of(
            PASS.code, PASS, NEED_CHANGE.code, NEED_CHANGE, REJECT.code, REJECT);

    private final String code;
    private final String label;
    private final CaseEvent event;

    ReviewAction(String code, String label, CaseEvent event) {
        this.code = code;
        this.label = label;
        this.event = event;
    }

    public String code() {
        return code;
    }

 /** Stored value for the review record (Chinese, consistent with the database design document). */
    public String label() {
        return label;
    }

    public CaseEvent event() {
        return event;
    }

 /** Parse according to the contract action values; throw 400 for unknown actions (boundary: reject invalid actions rather than silently accepting them). */
    public static ReviewAction from(String code) {
        ReviewAction action = BY_CODE.get(code);
        if (action == null) {
            throw new ServiceException("评审动作仅支持 pass/need_change/reject（当前：" + code + "）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return action;
    }
}
