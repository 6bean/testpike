package com.sixbean.testpike.core.script.domain.component;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;


final class FlowchartLabels {

    
    private static final Map<String, String> ACTION_LABELS = new LinkedHashMap<>();

    static {
        ACTION_LABELS.put("goto", "导航");
        ACTION_LABELS.put("click", "点击");
        ACTION_LABELS.put("input", "输入");
        ACTION_LABELS.put("expect", "断言");
        ACTION_LABELS.put("select", "选择");
        ACTION_LABELS.put("wait", "等待");
        ACTION_LABELS.put("screenshot", "截图");
        ACTION_LABELS.put("code.step", "自定义代码");
        ACTION_LABELS.put("visual.assert", "视觉断言");
        ACTION_LABELS.put("http.request", "接口调用");
        ACTION_LABELS.put("net.assert", "网络观测");
        ACTION_LABELS.put("sql.assert", "数据库校验");
        ACTION_LABELS.put("sql.exec", "数据操作");
        ACTION_LABELS.put("sql.query", "数据库取值");
        ACTION_LABELS.put("console.assert", "控制台断言");
    }

    
    private static final String[] NAMESPACE_PREFIXES = {"pos.", "erp.", "data.", "mobile."};

 
    private static final String[] LOCATOR_KEYS = {"text", "css", "accessibilityId", "url"};

    private FlowchartLabels() {
    }

    
    static String stepLabel(JsonNode step) {
        String action = step != null && step.isObject() ? step.path("action").asText("") : "";
        String verb = ACTION_LABELS.get(action);
        if (verb == null) {
            for (String prefix : NAMESPACE_PREFIXES) {
                if (action.startsWith(prefix)) {
                    verb = "业务域动作 " + action;
                    break;
                }
            }
        }
        if (verb == null) {
            verb = action.isBlank() ? "未知动作" : action;
        }
        String detail = detailOf(step);
        return detail == null ? verb : verb + " " + detail;
    }

 
    static String loopLabel(JsonNode step) {
        JsonNode count = step.get("count");
        if (count != null && count.isNumber()) {
            return "循环（count=" + count.asInt() + "）";
        }
        JsonNode items = step.get("items");
        if (items != null && items.isArray()) {
            return "循环（items 共 " + items.size() + " 项）";
        }
        return "循环";
    }

    
    private static String detailOf(JsonNode step) {
        if (step == null || !step.isObject()) {
            return null;
        }
        JsonNode target = step.get("target");
        if (target != null && target.isObject()) {
            for (String key : LOCATOR_KEYS) {
                JsonNode v = target.get(key);
                if (v != null && v.isTextual() && !v.asText().isBlank()) {
                    return key + '=' + v.asText();
                }
            }
        }
        JsonNode value = step.get("value");
        if (value != null && value.isTextual() && !value.asText().isBlank()) {
            return "值=" + value.asText();
        }
        return null;
    }
}
