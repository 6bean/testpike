package com.sixbean.testpike.aiservice.tool.domain.component;

import com.sixbean.testpike.common.util.LlmExtractMarkers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.ToolFcAssemblyRemoteItem;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatToolDef;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatToolFunction;
import com.sixbean.testpike.aiservice.tool.domain.FcPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <ul>
 * </ul>
 *
 */
@Component
public class LlmFunctionCalling {

    private static final Logger log = LoggerFactory.getLogger(LlmFunctionCalling.class);

    
    static final String TOOL_CHOICE_AUTO = "auto";

    private final CoreGateway coreGateway;
    private final ObjectMapper objectMapper;
    /** AP-5 catalog (nullable: absent = every tool stays resident, legacy shape). */
    private final FcToolCatalog catalog;

    /** Legacy 2-field wiring (tests/pre-AP-5 call sites): catalog inactive. */
    public LlmFunctionCalling(CoreGateway coreGateway, ObjectMapper objectMapper) {
        this(coreGateway, objectMapper, null);
    }

    @Autowired
    public LlmFunctionCalling(CoreGateway coreGateway, ObjectMapper objectMapper,
                              FcToolCatalog catalog) {
        this.coreGateway = coreGateway;
        this.objectMapper = objectMapper;
        this.catalog = catalog;
    }

    /**
 * <p>AO batch stage 1: the actor travels to the core assembly endpoint
 * (X-Operator-Id) so the five-way gate verdict applies server-side; a
 * 40301 from core refuses the whole plan at the consumption point — the
 * generation chain keeps that verdict, this facet never swallows it.
 * <p>AP-5 : above the threshold the prompt carries only the
 * core set + the search_tools meta def; the deferred candidates still ride
 * the index (authorization surface never shrinks with the prompt).
 */
    public FcPlan assemblePlan(long projectId, Long actorId) {
        List<ToolFcAssemblyRemoteItem> candidates =
                coreGateway.getProjectFcAssembly(projectId, actorId);
        List<ToolFcAssemblyRemoteItem> valid = new ArrayList<>();
        Map<Long, JsonNode> paramsByToolId = new LinkedHashMap<>();
        for (ToolFcAssemblyRemoteItem item : candidates) {
            if (item.type() != TYPE_EXECUTABLE) {
                continue;
            }
            JsonNode parameters = FcSchemaReader.read(objectMapper, item);
            if (parameters == null) {
                continue;
            }
            valid.add(item);
            paramsByToolId.put(item.toolId(), parameters);
        }
        if (valid.isEmpty()) {
            return FcPlan.EMPTY;
        }
        // Wire alias single source (FcWireNames): strict providers 400 on catalog
        // names that break ^[A-Za-z0-9_-]{1,64}$; the catalog itself is an outward
        // contract and stays untouched. Legal names pass through unchanged.
        Map<String, String> wireByOriginal = FcWireNames.assign(
                valid.stream().map(ToolFcAssemblyRemoteItem::name).toList());
        Map<String, ChatToolDef> defsByName = new LinkedHashMap<>();
        for (ToolFcAssemblyRemoteItem item : valid) {
            String wire = wireByOriginal.get(item.name());
            defsByName.put(wire, new ChatToolDef(
                    new ChatToolFunction(wire, null, paramsByToolId.get(item.toolId()))));
        }
        // Response-side routing keeps BOTH keys: the original catalog name (still
        // the name the catalog note and stub replays speak) and the wire alias
        // the provider echoes back; neither widens beyond the assembled facts.
        Map<String, ToolFcAssemblyRemoteItem> index = new LinkedHashMap<>();
        for (ToolFcAssemblyRemoteItem item : valid) {
            index.put(item.name(), item);
            String wire = wireByOriginal.get(item.name());
            if (!wire.equals(item.name())) {
                index.putIfAbsent(wire, item);
            }
        }
        List<ChatToolDef> tools = new ArrayList<>();
        List<ToolFcAssemblyRemoteItem> deferred = List.of();
        if (catalog != null && catalog.active(valid.size())) {
            Set<String> coreNames = catalog.coreToolNames();
            List<ToolFcAssemblyRemoteItem> deferredItems = new ArrayList<>();
            for (ToolFcAssemblyRemoteItem item : valid) {
                if (coreNames.contains(item.name())) {
                    tools.add(defsByName.get(wireByOriginal.get(item.name())));
                } else {
                    deferredItems.add(item);
                }
            }
            tools.add(catalog.searchToolDef());
            deferred = List.copyOf(deferredItems);
        } else {
            tools.addAll(defsByName.values());
        }
        return new FcPlan(List.copyOf(tools), Map.copyOf(index), deferred);
    }

    
    private static final int TYPE_EXECUTABLE = 2;

    
    public ChatCompletionRequest decorate(ChatCompletionRequest request, FcPlan plan) {
        if (plan == null || plan.isEmpty()) {
            return request;
        }
        String note = catalogNoteFor(plan);
        List<ChatMessage> messages = new ArrayList<>(request.messages().size() + 1);
        boolean injected = false;
        for (ChatMessage m : request.messages()) {
            if (!injected && "system".equals(m.role())) {
                messages.add(withFcMarker(m, note));
                injected = true;
            } else {
                messages.add(m);
            }
        }
        if (!injected) {
            messages.add(0, new ChatMessage("system", fcMarkerInstruction() + note));
        }
        return new ChatCompletionRequest(request.model(), List.copyOf(messages),
                request.temperature(), request.maxTokens(), plan.tools(), TOOL_CHOICE_AUTO,
                null, null, null, request.responseFormat());
    }

    /** Compact catalog lines (empty when the plan keeps everything resident). */
    private String catalogNoteFor(FcPlan plan) {
        return catalog != null && !plan.deferred().isEmpty()
                ? catalog.catalogNote(plan.deferred()) : "";
    }

    
    private static ChatMessage withFcMarker(ChatMessage system, String catalogNote) {
        String content = system.content() == null ? "" : system.content();
        if (content.contains(LlmExtractMarkers.FC_TOOL_CALL)) {
            return system;
        }
        return new ChatMessage("system", content + "\n\n" + fcMarkerInstruction() + catalogNote);
    }

    private static String fcMarkerInstruction() {
        return "可按需调用 tools 清单中的工具获取外部能力（契约位 " + LlmExtractMarkers.FC_TOOL_CALL
                + "）：需要外部数据时发起 tool_call，等待工具结果消息后继续推理。";
    }
}
