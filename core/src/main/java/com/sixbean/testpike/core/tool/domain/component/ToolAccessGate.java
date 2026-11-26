package com.sixbean.testpike.core.tool.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.pipeline.DenialGuidanceFrame;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.notification.domain.component.AdminAlertRecipients;
import com.sixbean.testpike.core.project.domain.component.ProjectRoleResolver;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Five-way tool gate facets for the dialog/FC chain (AO batch stage 1): project
 * visibility of the assembly segment and the execution-class switch. The assembly
 * view ({@code ToolFcAssemblyService}) and the invoke endpoint
 * ({@code ToolExecutionService}) both consume this single component so the
 * verdict exists exactly once — no duplicated predicates across consumers.
 * <p>Visibility verdict source = {@link ProjectRoleResolver#isMember} ∪ system
 * administrators ({@link AdminAlertRecipients#adminUserIds}, the same
 * admin-set reuse precedent as ApiTokenService). A null actor keeps the legacy
 * behaviour untouched (identity injection tightens that seam in stage 3).
 * <p>Execution-class family = exec_kind EXECUTOR only (tp_tool.exec_kind=2:
 * trigger batches, launch pressure tests, device actions). GENKIT/HTTP/MCP
 * shapes are not state-changing actions per the batch ruling.
 */
@Component
public class ToolAccessGate {

    /** K8 key of the dialog execution-class master switch (hot-updatable). */
    public static final String KEY_EXEC_ENABLED = "ai.chat.exec-enabled";

    private final ProjectRoleResolver projectRoleResolver;
    private final AdminAlertRecipients adminAlertRecipients;
    private final ConfigService configService;

    public ToolAccessGate(ProjectRoleResolver projectRoleResolver,
                          AdminAlertRecipients adminAlertRecipients,
                          ConfigService configService) {
        this.projectRoleResolver = projectRoleResolver;
        this.adminAlertRecipients = adminAlertRecipients;
        this.configService = configService;
    }

    /**
 * Project-slice visibility check used by the assembly endpoint. System
 * admins see every segment; project members see their own segment; everyone
 * else gets 40301 with a message that never names project content
 * (horizontal-privilege refusal must not leak what exists).
 *
 * @param projectId tp_project.id; null = tool has no project segment, legacy pass
 * @param actorId acting user; null = legacy/system caller, pass (stage 3)
 */
    public void requireProjectVisible(Long projectId, Long actorId) {
        if (actorId == null || projectId == null) {
            return;
        }
        if (projectRoleResolver.isMember(projectId, actorId)
                || adminAlertRecipients.adminUserIds().contains(actorId)) {
            return;
        }
        throw new ServiceException("无权访问该项目的工具装配：仅项目成员或系统管理员可查看（项目 id="
                + projectId + "）——请前往「工具管理」切换到您所属的项目，"
                + "或请项目负责人在「成员管理」中把您添加为成员", BizErrorCode.FORBIDDEN);
    }

    /** Execution-class master switch state (single key source, factory default on). */
    public boolean execSwitchOn() {
        return "true".equalsIgnoreCase(configService.getValue(KEY_EXEC_ENABLED, "true"));
    }

    /** Execution-class shape check (exec_kind=2 EXECUTOR only; null-safe). */
    public static boolean isExecClass(Integer execKindCode) {
        return execKindCode != null && execKindCode == ToolExecKind.EXECUTOR.code();
    }

    /**
 * Per-call re-check at the invoke execution point (not assembly-only):
 * project visibility of the tool's segment plus the hot-updatable switch for
 * execution-class tools. Refusal lands before any trace row is written, so
 * a guarded invoke never pretends to run.
 * <p>The off-message names where to turn the switch on and the literal key
 * (honest guidance contract), matching the coarse dialog gate wording
 * without fighting it — the coarse gate refuses the whole run, this facet
 * refuses the single action for non-dialog callers (generation chain).
 */
    public void requireInvokeAllowed(Long projectId, Long actorId, Integer execKind,
                                     String toolName) {
        requireProjectVisible(projectId, actorId);
        if (isExecClass(execKind) && !execSwitchOn()) {
            throw new ServiceException(DenialGuidanceFrame.switchClosed(
                    "工具「" + toolName + "」执行类动作", KEY_EXEC_ENABLED),
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    /**
 * Reserved identity keys the model must never bring on its own — the
 * server injects project/actor facts at the execution point (AO batch
 * stage 3). A dirty value that contradicts the trusted
 * ledger row is refused before any downstream execution.
 */
    static final Set<String> RESERVED_IDENTITY_KEYS =
            Set.of("projectId", "operatorId", "actorId", "userId");

    /**
 * Detect forged identity keys inside tool arguments (AI invoke chain only).
 * <p>Contract: absent keys = legal (server keeps injecting the trusted
 * values today); a present key whose text form differs from the trusted
 * fact = tampering. The trusted project fact is the ledger row
 * ({@code toolProjectId}) when the row carries one; a system-level row
 * (project_id NULL — e.g. the dialog OCR read doors seeded by ) has
 * no segment of its own, so the value asserted by the server-built invoke
 * carrier ({@code assertedProjectId}, a field the model can never write)
 * is the trusted fact instead. No carrier anchor and no ledger row keeps
 * every project claim refused — the smuggling door stays shut (R51 shape
 * knowledge at the single consumption point).
 * <p>Returns a human message naming every
 * offending key, or null when the payload is clean / not a JSON object
 * (shape violations stay on the existing execute-side guard).
 * <p>The caller converts a non-null verdict into a refused run WITH trace
 * and audit — deliberately different from the zero-trace visibility guard:
 * a tampered payload is an event worth recording (ruling: refuse and keep
 * the original payload digest).
 */
    public String findIdentityTampering(String inputJson, Long toolProjectId,
                                        Long assertedProjectId, Long trustedActorId) {
        if (inputJson == null || inputJson.isBlank()) {
            return null;
        }
        JsonNode root;
        try {
            root = TAMPER_READER.readTree(inputJson);
        } catch (Exception e) {
            return null; // not JSON at all — execute-side guard owns that path
        }
        if (!root.isObject()) {
            return null;
        }
        List<String> offenders = new ArrayList<>();
        Long trustedProject = toolProjectId != null ? toolProjectId : assertedProjectId;
        String trustedProjectText = trustedProject == null ? null : String.valueOf(trustedProject);
        String trustedActor = trustedActorId == null ? null : String.valueOf(trustedActorId);
        for (String key : RESERVED_IDENTITY_KEYS) {
            JsonNode value = root.get(key);
            if (value == null || value.isNull()) {
                continue;
            }
            String claimed = value.asText();
            String trusted = "projectId".equals(key) ? trustedProjectText : trustedActor;
            if (trusted == null || !trusted.equals(claimed)) {
                offenders.add(key + "=" + claimed);
            }
        }
        if (offenders.isEmpty()) {
            return null;
        }
        return "参数中的项目/操作者身份由服务端注入，不接受模型自带值——请移除 "
                + String.join("、", offenders) + " 后重试";
    }

    private static final ObjectMapper TAMPER_READER = new ObjectMapper();
}
