package com.sixbean.testpike.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;


class StepAliasContractTest {

    @Test
    void 合法别名_中文与标识符形态过闸() {
        assertNull(StepAliasContract.violation("创建订单"));
        assertNull(StepAliasContract.violation("order-no"));
        assertNull(StepAliasContract.violation("v2"));
        assertNull(StepAliasContract.violation("库存99"));
    }

    @Test
    void 纯数字别名_stepNo直址形冲突拒() {
        assertNotNull(StepAliasContract.violation("3"));
        assertNotNull(StepAliasContract.violation("12"));
        String msg = StepAliasContract.violation("3");
        assertNotNull(msg);
        assertNotNull(msg.indexOf("步骤号") >= 0 || msg.indexOf("纯数字") >= 0 ? msg : null);
    }

    @Test
    void 保留命名空间撞名拒() {
        for (String reserved : new String[]{"ENV", "DATA", "PACK", "VAR", "SESSION", "RUN", "global", "CRED"}) {
            String violation = StepAliasContract.violation(reserved);
            assertNotNull(violation, "保留字别名须拒: " + reserved);
            assertEquals(true, violation.contains("保留"), "须人话点名保留命名空间: " + violation);
        }
    }

    @Test
    void 含点号别名_字段分隔符劫持拒() {
        assertNotNull(StepAliasContract.violation("创建订单.订单号"));
        assertNotNull(StepAliasContract.violation("a.b"));
    }

    @Test
    void 空白与非法起头_拒() {
        assertNotNull(StepAliasContract.violation(null));
        assertNotNull(StepAliasContract.violation("  "));
        assertNotNull(StepAliasContract.violation(".订单号"));
        assertNotNull(StepAliasContract.violation("-订单号"));
    }
}
