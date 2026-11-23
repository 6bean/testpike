package com.sixbean.testpike.aiservice.explore.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatToolDef;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatToolFunction;
import com.sixbean.testpike.common.util.ExploreEnvelopeContract;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;


@Component
public class ExploreBuiltinToolCatalog {

    
    private static final String URL_PROP =
            "\"url\": {\"type\": \"string\", \"description\": \"http(s) 绝对地址（每步必须自带）\"}";

    
    private static final String TARGET_OBJECT =
            "\"target\": {\"type\": \"object\", \"description\": \"元素定位（selector 优先）\", "
                    + "\"properties\": {\"selector\": {\"type\": \"string\"}, "
                    + "\"text\": {\"type\": \"string\"}}}";

    private final ObjectMapper objectMapper;

    public ExploreBuiltinToolCatalog(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    
    public List<ChatToolDef> toolDefs() {
        return List.of(
                def("page-snapshot", "读取目标页面 DOM 可见文本快照（探索观测首选动作）",
                        URL_PROP, "[\"url\"]"),
                def("element-candidates", "只读探查目标页面，返回可交互元素的定位候选"
                        + "列表（id/data-test/name/placeholder/文本 + 可见性，"
                        + "生成定位策略前先验证元素真实存在）",
                        URL_PROP, "[\"url\"]"),
                def("click-path", "打开目标页面并点击指定元素，返回动作结果",
                        URL_PROP + ", " + TARGET_OBJECT, "[\"url\", \"target\"]"),
                def("input-path", "打开目标页面并在输入框填写文本（配合 assert 验证表单行为）",
                        URL_PROP + ", " + TARGET_OBJECT
                                + ", \"value\": {\"type\": \"string\", \"description\": \"要输入的文本\"}",
                        "[\"url\", \"target\", \"value\"]"),
                def("assert", "打开目标页面断言期望文本出现（观测事实）",
                        URL_PROP + ", \"text\": {\"type\": \"string\", "
                                + "\"description\": \"期望出现的页面文本\"}",
                        "[\"url\", \"text\"]"));
    }

 
    public List<ChatToolDef> mobileToolDefs() {
        List<ChatToolDef> defs = new ArrayList<>();
        for (String action : ExploreEnvelopeContract.MOBILE_ROAM_ACTIONS) {
            defs.add(switch (action) {
                case "tree-snapshot" -> def(action,
                        "读取当前界面无障碍树快照（移动探索观测首选动作，含元素文本/可点击性/坐标）",
                        "", "[]");
                case "tap" -> def(action, "点击无障碍树中的目标元素",
                        "\"target\": {\"type\": \"object\", \"description\": "
                                + "\"元素定位（text/resource-id/bounds 任一）\", \"properties\": "
                                + "{\"text\": {\"type\": \"string\"}, \"resourceId\": "
                                + "{\"type\": \"string\"}}}", "[\"target\"]");
                case "swipe" -> def(action, "在屏幕上滑动（direction=up/down/left/right）",
                        "\"direction\": {\"type\": \"string\", \"description\": "
                                + "\"滑动方向 up/down/left/right\"}", "[\"direction\"]");
                default -> def(action, "back".equals(action)
                        ? "系统返回键（回上一界面）" : "系统 Home 键（回桌面，用于起点复位）",
                        "", "[]");
            });
        }
        return List.copyOf(defs);
    }

    private ChatToolDef def(String name, String description, String propertiesJson,
                            String requiredJson) {
        String schema = "{\"type\": \"object\", \"properties\": {" + propertiesJson
                + "}, \"required\": " + requiredJson + "}";
        return new ChatToolDef(new ChatToolFunction(name, description, parse(name, schema)));
    }

    private JsonNode parse(String name, String schemaJson) {
        try {
            return objectMapper.readTree(schemaJson);
        } catch (Exception e) {
            throw new IllegalStateException("内置探索工具 schema 非法（开发期缺陷）" + name + ": "
                    + e.getMessage(), e);
        }
    }
}
