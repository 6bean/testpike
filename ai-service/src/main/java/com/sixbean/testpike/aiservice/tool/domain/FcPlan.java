package com.sixbean.testpike.aiservice.tool.domain;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.ToolFcAssemblyRemoteItem;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatToolDef;

import java.util.List;
import java.util.Map;


public record FcPlan(List<ChatToolDef> tools, Map<String, ToolFcAssemblyRemoteItem> index,
                     List<ToolFcAssemblyRemoteItem> deferred) {

    /** Two-field shape (everything resident) — pre-AP-5 construction sites untouched. */
    public FcPlan(List<ChatToolDef> tools, Map<String, ToolFcAssemblyRemoteItem> index) {
        this(tools, index, List.of());
    }

    
    public static final FcPlan EMPTY = new FcPlan(List.of(), Map.of());

    public boolean isEmpty() {
        return tools.isEmpty();
    }
}
