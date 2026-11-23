package com.sixbean.testpike.common.util;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * <ul>
 * </ul>
 */
public final class VisualAssertContract {

    
    public static final double TOLERANCE_MAX = 1.0;

    private VisualAssertContract() {
    }

    
    public static String violation(JsonNode step) {
        String keyViolation = baselineKeyViolation(step.get("baselineKey"));
        if (keyViolation != null) {
            return keyViolation;
        }
        String toleranceViolation = toleranceViolation(step.get("tolerance"));
        if (toleranceViolation != null) {
            return toleranceViolation;
        }
        return regionViolation(step.get("region"));
    }

    
    private static String baselineKeyViolation(JsonNode baselineKey) {
        if (baselineKey == null || baselineKey.isNull()) {
            return null;
        }
        if (!baselineKey.isTextual() || baselineKey.asText().isBlank()) {
            return "visual.assert 的 baselineKey 非法（给定时须为非空字符串基线对象键，"
                    + "缺省=按步骤稳定键自动定位当前生效基线）";
        }
        return null;
    }

    
    private static String toleranceViolation(JsonNode tolerance) {
        if (tolerance == null || tolerance.isNull()) {
            return null;
        }
        if (!tolerance.isNumber() || tolerance.asDouble() < 0 || tolerance.asDouble() > TOLERANCE_MAX) {
            return "visual.assert 的 tolerance 非法（" + tolerance.asText()
                    + "）——须为 0-1 之间的数值（差异像素占比，0=逐像素全等，0.02=容忍 2% 差异）";
        }
        return null;
    }

    
    private static String regionViolation(JsonNode region) {
        if (region == null || region.isNull()) {
            return null;
        }
        if (!region.isObject()) {
            return regionError(region.asText());
        }
        for (String field : new String[]{"x", "y", "w", "h"}) {
            JsonNode value = region.get(field);
            if (value == null || !value.isIntegralNumber()) {
                return regionError(field + "=" + (value == null ? "缺失" : value.asText()));
            }
        }
        long x = region.get("x").asLong();
        long y = region.get("y").asLong();
        long w = region.get("w").asLong();
        long h = region.get("h").asLong();
        if (x < 0 || y < 0 || w < 1 || h < 1) {
            return regionError("x=" + x + ",y=" + y + ",w=" + w + ",h=" + h);
        }
        return null;
    }

    private static String regionError(String detail) {
        return "visual.assert 的 region 非法（" + detail + "）——须为 {x,y,w,h} 四个整数字段"
                + "（坐标非负、宽高至少 1 像素；缺省=全图比对）";
    }
}
