package com.sixbean.testpike.core.webelement.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Refusal-guidance contract for the element lifecycle transition table:
 * every on-screen refusal must name the offending value, the allowed
 * values, and the reachable UI entry (the Page Element Library row
 * action), per the error-guidance rule. Assertions fail against wording
 * that only reports the state without telling the user where to fix it.
 */
class WebElementLifecycleRefusalGuidanceTest {

    private final WebElementLifecycle lifecycle = new WebElementLifecycle();

    @Test
    void outOfRangeRefusalNamesTheValueAndThePanelEntry() {
        ServiceException refused = assertThrows(ServiceException.class,
                () -> lifecycle.requireAllowed(WebElementLifecycle.CONFIRMED, 9));
        assertEquals(BizErrorCode.VALIDATION_ERROR, refused.getCode());
        assertTrue(refused.getMessage().contains("当前 9"),
                "refusal must echo the submitted value: " + refused.getMessage());
        assertTrue(refused.getMessage().contains("0=候选"),
                "refusal must list allowed values: " + refused.getMessage());
        assertTrue(refused.getMessage().contains("请"),
                "refusal must carry a next-action sentence: " + refused.getMessage());
        assertTrue(refused.getMessage().contains("页面元素库"),
                "refusal must name the reachable panel: " + refused.getMessage());
    }

    @Test
    void illegalMoveRefusalNamesBothStatesAndPointsToThePanel() {
        ServiceException refused = assertThrows(ServiceException.class,
                () -> lifecycle.requireAllowed(WebElementLifecycle.RETIRED,
                        WebElementLifecycle.CONFIRMED));
        assertEquals(BizErrorCode.STATE_MACHINE_REJECT, refused.getCode());
        assertTrue(refused.getMessage().contains("退役"),
                "refusal must name the current state: " + refused.getMessage());
        assertTrue(refused.getMessage().contains("请改成"),
                "refusal must suggest the fix verb: " + refused.getMessage());
        assertTrue(refused.getMessage().contains("页面元素库"),
                "refusal must name the reachable panel: " + refused.getMessage());
    }
}
