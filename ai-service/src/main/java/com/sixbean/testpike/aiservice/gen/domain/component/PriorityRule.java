package com.sixbean.testpike.aiservice.gen.domain.component;

import java.util.List;


public class PriorityRule {

    private static final List<String> P0_KEYWORDS = List.of(
            "登录", "支付", "下单", "结算", "权限", "安全", "校验", "退款", "对账", "库存扣减");
    private static final List<String> P2_KEYWORDS = List.of(
            "展示", "样式", "美化", "提示语", "边缘", "次要", "文案调整", "排序显示");

    public int suggest(String requirement) {
        if (requirement == null || requirement.isBlank()) {
            return 1;
        }
        for (String kw : P0_KEYWORDS) {
            if (requirement.contains(kw)) {
                return 0;
            }
        }
        for (String kw : P2_KEYWORDS) {
            if (requirement.contains(kw)) {
                return 2;
            }
        }
        return 1;
    }
}
