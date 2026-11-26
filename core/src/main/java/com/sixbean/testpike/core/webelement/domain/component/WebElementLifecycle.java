package com.sixbean.testpike.core.webelement.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * Element lifecycle transition table (single source, enforced service-side;
 * the tp_web_element CHECK only bounds the domain).
 *
 * <pre>
 * candidate --execution verified--> confirmed
 * confirmed --all anchors missed / miss threshold--> stale
 * stale     --repaired anchor batch--> confirmed
 * stale     --not seen again + human batch--> retired
 * candidate --TTL sweep / human retire--> retired
 * </pre>
 *
 * retired is terminal. Anything off the table refuses with the shared
 * 40902 state-machine code so callers can branch on it.
 */
@Component
public class WebElementLifecycle {

    public static final int CANDIDATE = 0;
    public static final int CONFIRMED = 1;
    public static final int STALE = 2;
    public static final int RETIRED = 3;

    private static final Map<Integer, Set<Integer>> ALLOWED = Map.of(
            CANDIDATE, Set.of(CONFIRMED, RETIRED),
            CONFIRMED, Set.of(STALE, RETIRED),
            STALE, Set.of(CONFIRMED, RETIRED),
            RETIRED, Set.of());

    /** Refuse with 40902 unless the move is on the table; unknown states refuse with 40001. */
    public void requireAllowed(int from, int to) {
        if (to < CANDIDATE || to > RETIRED) {
            throw new ServiceException("元素状态取值超出范围（当前 %s，允许：0=候选、1=已确认、2=陈旧、3=退役）——请改为允许值，或先在「页面元素库」列表核对该元素当前状态再做状态操作",
                    BizErrorCode.VALIDATION_ERROR, to);
        }
        if (!ALLOWED.getOrDefault(from, Set.of()).contains(to)) {
            throw new ServiceException("元素状态不允许从「%s」迁移到「%s」（候选→已确认/退役，已确认→陈旧/退役，陈旧→已确认/退役，退役为终态）——请改成允许的走向，或先在「页面元素库」查看该元素当前状态后再发起",
                    BizErrorCode.STATE_MACHINE_REJECT, name(from), name(to));
        }
    }

    /** Human-readable state name shared by refusal messages and event summaries. */
    public String name(int state) {
        return switch (state) {
            case CANDIDATE -> "候选";
            case CONFIRMED -> "已确认";
            case STALE -> "陈旧";
            case RETIRED -> "退役";
            default -> "未知(" + state + ")";
        };
    }
}
