package com.sixbean.testpike.common.util;

import java.util.regex.Pattern;


public final class StepAliasContract {

    
    static final Pattern ALIAS_NAME = ActionJsonValidator.ROLE_NAME;

    
    private static final Pattern STEP_NO_FORM = Pattern.compile("[0-9]+");

    
    static final java.util.Set<String> RESERVED = ActionJsonValidator.RESERVED_ROLE_NAMES;

    private StepAliasContract() {
    }

    
    public static String violation(String aliasName) {
        if (aliasName == null || aliasName.isBlank()) {
            return "别名不能为空（引用变量时显示的易读名，如「创建订单」「订单号」）";
        }
        if (STEP_NO_FORM.matcher(aliasName).matches()) {
            return "别名「" + aliasName + "」是纯数字——与步骤号直址写法（{{" + aliasName
                    + ".字段}}）冲突，路径改版不断链的收益会被数字名劫持；请改用业务名称";
        }
        if (RESERVED.contains(aliasName)) {
            return "别名「" + aliasName + "」撞保留命名空间（" + String.join("/", RESERVED)
                    + "）——这些名字是变量引用语法本体，不能作别名";
        }
        if (!ALIAS_NAME.matcher(aliasName).matches()) {
            return "别名「" + aliasName + "」词法非法（须字母/数字/下划线/中文开头，"
                    + "可含连字符，不含点号——点号是「别名.字段」的分隔符）";
        }
        return null;
    }
}
