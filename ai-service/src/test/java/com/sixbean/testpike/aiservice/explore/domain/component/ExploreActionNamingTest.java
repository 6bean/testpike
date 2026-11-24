package com.sixbean.testpike.aiservice.explore.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatToolDef;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatToolFunction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Contract for the process-event human action label: labels derive from the
 * visible tool definitions (single source), clip at the first explanatory
 * delimiter, and fall back to a neutral business phrase for unknown names —
 * a raw machine name must never reach the screen face.
 */
class ExploreActionNamingTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ChatToolDef tool(String name, String description) throws Exception {
        return new ChatToolDef(new ChatToolFunction(name, description,
                mapper.readTree("{\"type\":\"object\",\"properties\":{}}")));
    }

    @Test
    void knownToolLabelCutsFirstClause() throws Exception {
        List<ChatToolDef> tools = List.of(
                tool("click-path", "打开目标页面并点击指定元素，返回动作结果"));
        assertEquals("打开目标页面并点击指定元素",
                ExploreActionNaming.humanLabel("click-path", tools));
    }

    @Test
    void unknownToolFallsBackToBusinessPhraseNotRawName() throws Exception {
        List<ChatToolDef> tools = List.of(tool("page-snapshot", "读取目标页面 DOM 可见文本快照"));
        assertEquals(ExploreActionNaming.UNKNOWN_LABEL,
                ExploreActionNaming.humanLabel("login-tool", tools));
        assertEquals(ExploreActionNaming.UNKNOWN_LABEL,
                ExploreActionNaming.humanLabel(null, tools));
        assertEquals(ExploreActionNaming.UNKNOWN_LABEL,
                ExploreActionNaming.humanLabel("x", null));
    }

    @Test
    void longDescriptionClipsToLabelCap() throws Exception {
        List<ChatToolDef> tools = List.of(tool("wide",
                "一二三四五六七八九十一二三四五六七八九十超长的工具说明尾巴"));
        String label = ExploreActionNaming.humanLabel("wide", tools);
        assertEquals(20, label.length(), "上屏动作名必须收在短标签上限内: " + label);
    }
}
