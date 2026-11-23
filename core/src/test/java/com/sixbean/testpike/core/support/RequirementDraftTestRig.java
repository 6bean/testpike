package com.sixbean.testpike.core.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.aichat.domain.service.ChatFrameService;
import com.sixbean.testpike.core.aichat.domain.service.ChatSessionService;
import com.sixbean.testpike.core.aichat.domain.service.LedgerSessionSeed;
import com.sixbean.testpike.core.aichat.domain.component.ChatFrameType;
import com.sixbean.testpike.core.requirement.api.dto.RequirementCreateInput;
import com.sixbean.testpike.core.requirement.api.dto.RequirementInfo;
import com.sixbean.testpike.core.requirement.domain.service.RequirementService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Single scaffolding rig for the requirement-draft dialog spectra (AL batch
 * /): both suites used to re-implement the same session fixture,
 * frame landing, requirement seeding and config pinning, which the
 * duplication gate flagged as a parallel-write hazard. The rig owns that
 * plumbing once; suites keep only their own assertions and payloads.
 * <p>All writes go through the real services and the real frame ledger —
 * zero mocks. Every row the rig creates is collected by id and removed in
 * {@link #closeSession}; factory tables are never truncated.
 */
public final class RequirementDraftTestRig {

    private final ChatSessionService sessionService;
    private final ChatFrameService frameService;
    private final RequirementService requirementService;
    private final JdbcTemplate jdbcTemplate;
    private final LedgerSessionSeed seed;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final List<Long> createdRequirementIds = new ArrayList<>();
    private final List<Long> sideProjectIds = new ArrayList<>();

    private String tag;
    private long projectId;
    private long userId;
    private long sessionId;

    private RequirementDraftTestRig(ChatSessionService sessionService, ChatFrameService frameService,
            RequirementService requirementService, JdbcTemplate jdbcTemplate,
            LedgerSessionSeed seed) {
        this.sessionService = sessionService;
        this.frameService = frameService;
        this.requirementService = requirementService;
        this.jdbcTemplate = jdbcTemplate;
        this.seed = seed;
    }

    /** Assemble the rig from the beans the Spring test context already injects. */
    public static RequirementDraftTestRig of(ChatSessionService sessionService,
            ChatFrameService frameService, RequirementService requirementService,
            JdbcTemplate jdbcTemplate, LedgerSessionSeed seed) {
        return new RequirementDraftTestRig(sessionService, frameService, requirementService,
                jdbcTemplate, seed);
    }

    /** Seed user + project + chat session under one unique tag prefix. */
    public void openSession(String tagPrefix, String sessionLabel) {
        // Single-sourced scope seeding (R40): the three-row seed lives in
        // LedgerSessionSeed, shared with the dialog suites that need the same shape.
        LedgerSessionSeed.Scope scope = seed.open(tagPrefix, sessionLabel,
                "{\"route\":\"/requirements\"}");
        tag = scope.tag();
        userId = scope.userId();
        projectId = scope.projectId();
        sessionId = scope.sessionId();
    }

    /** Delete every row this rig created, strictly by collected id. */
    public void closeSession() {
        jdbcTemplate.update("DELETE FROM tp_ai_chat_draft WHERE session_id = ?", sessionId);
        jdbcTemplate.update("DELETE FROM tp_ai_chat_frame WHERE session_id = ?", sessionId);
        jdbcTemplate.update("DELETE FROM tp_ai_chat_message WHERE session_id = ?", sessionId);
        jdbcTemplate.update("DELETE FROM tp_ai_chat_session WHERE id = ?", sessionId);
        for (long requirementId : createdRequirementIds) {
            jdbcTemplate.update("DELETE FROM tp_case_requirement WHERE requirement_id = ?", requirementId);
            jdbcTemplate.update("DELETE FROM tp_requirement_version WHERE requirement_id = ?", requirementId);
            jdbcTemplate.update("DELETE FROM tp_requirement WHERE id = ?", requirementId);
        }
        for (long sideProject : sideProjectIds) {
            jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", sideProject);
        }
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_user WHERE id = ?", userId);
    }

    /** Land a REQUIREMENT_WRITE draft with a proposal body through the real frame ledger. */
    public long landRequirementDraft(long targetId, int baseVersion, String payloadJson) {
        Map<String, Object> meta = draftMeta(targetId, baseVersion);
        meta.put("model", "test-model");
        meta.put("actionJson", payloadJson);
        long message = appendDraftFrame(meta);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM tp_ai_chat_draft WHERE message_id = ?", Long.class, message);
    }

    /** Append a DRAFT frame whose meta lacks a proposal body; returns the message id. */
    public long landDraftWithoutProposal(long targetId, int baseVersion) {
        return appendDraftFrame(draftMeta(targetId, baseVersion));
    }

    /** Create a requirement in the session's project and track it for cleanup. */
    public long seedRequirement(String title) {
        RequirementInfo created = requirementService.create(userId,
                new RequirementCreateInput(projectId, 3, null, title, "seed criteria", "1.0"));
        createdRequirementIds.add(created.id());
        return created.id();
    }

    /** Pin a hot config key to an explicit value for one test. */
    public void pinConfig(String key, String value) {
        jdbcTemplate.update("""
                INSERT INTO tp_sys_config (config_key, config_value, description, group_name)
                VALUES (?, ?, 'test-pinned', 'ai')
                ON CONFLICT (config_key) DO UPDATE SET config_value = EXCLUDED.config_value
                """, key, value);
    }

    /** Track a requirement created outside the seeding helper (receipt ids). */
    public void trackRequirement(long requirementId) {
        createdRequirementIds.add(requirementId);
    }

    /** Track an auxiliary project the test creates for attribution probes. */
    public void trackSideProject(long sideProjectId) {
        sideProjectIds.add(sideProjectId);
    }

    public String tag() {
        return tag;
    }

    public long projectId() {
        return projectId;
    }

    public long userId() {
        return userId;
    }

    public long sessionId() {
        return sessionId;
    }

    public List<Long> createdRequirementIds() {
        return List.copyOf(createdRequirementIds);
    }

    private Map<String, Object> draftMeta(long targetId, int baseVersion) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("objectType", "REQUIREMENT_WRITE");
        meta.put("targetId", targetId);
        meta.put("baseVersion", baseVersion);
        return meta;
    }

    private long appendDraftFrame(Map<String, Object> meta) {
        long message = frameService.openMessage(sessionId, "assistant", null);
        try {
            frameService.appendFrame(sessionId, message, ChatFrameType.DRAFT,
                    "requirement write proposal", objectMapper.writeValueAsString(meta));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return message;
    }
}
