package com.sixbean.testpike.admin.cases.api.controller;

import com.sixbean.testpike.admin.audit.infrastructure.remote.client.CoreAuditClient;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.cases.api.dto.CaseView;
import com.sixbean.testpike.admin.cases.api.dto.FlowchartView;
import com.sixbean.testpike.admin.cases.api.dto.PreviewFlowchartRequest;
import com.sixbean.testpike.admin.cases.api.dto.SaveScriptRequest;
import com.sixbean.testpike.admin.cases.api.dto.ScriptView;
import com.sixbean.testpike.admin.cases.api.dto.ScriptVersionDiffView;
import com.sixbean.testpike.admin.cases.api.dto.ScriptVersionView;
import com.sixbean.testpike.admin.cases.api.dto.TrialRunRequest;
import com.sixbean.testpike.admin.cases.infrastructure.remote.client.CoreCaseClient;
import com.sixbean.testpike.admin.cases.infrastructure.remote.client.CoreScriptClient;
import com.sixbean.testpike.admin.executor.infrastructure.remote.client.CoreExecClient;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;


@RestController
@RequestMapping("/api/v1/cases")
public class ScriptController {

    
    private static final int GENERATED_BY_MANUAL =
            com.sixbean.testpike.common.util.ScriptSourceContract.GENERATED_BY_MANUAL;

    private final CoreScriptClient coreScriptClient;
    private final CoreCaseClient coreCaseClient;
    private final CoreExecClient coreExecClient;
    private final ProjectAccessGuard guard;
    private final CoreAuditClient coreAuditClient;

    public ScriptController(CoreScriptClient coreScriptClient, CoreCaseClient coreCaseClient,
                            CoreExecClient coreExecClient,
                            ProjectAccessGuard guard, CoreAuditClient coreAuditClient) {
        this.coreScriptClient = coreScriptClient;
        this.coreCaseClient = coreCaseClient;
        this.coreExecClient = coreExecClient;
        this.guard = guard;
        this.coreAuditClient = coreAuditClient;
    }

    
    @GetMapping("/{id}/script")
    public Response<ScriptView> getScript(@PathVariable long id) {
        CaseView view = coreCaseClient.getCase(id);
        guard.assertCanView(UserContext.current(), view.projectId());
        return Response.builderSuccess(coreScriptClient.getScript(id));
    }

    
    @GetMapping("/{id}/scripts/versions")
    @RequirePermission("perm:script:script-version")
    public Response<List<ScriptVersionView>> listScriptVersions(@PathVariable long id) {
        CaseView view = coreCaseClient.getCase(id);
        guard.assertCanView(UserContext.current(), view.projectId());
        return Response.builderSuccess(coreScriptClient.listVersions(id));
    }

    
    @GetMapping("/{id}/scripts/versions/{v}/diff")
    @RequirePermission("perm:script:script-version")
    public Response<ScriptVersionDiffView> diffScriptVersion(@PathVariable long id, @PathVariable int v,
                                                             @RequestParam(required = false) Integer baseVersion) {
        CaseView view = coreCaseClient.getCase(id);
        guard.assertCanView(UserContext.current(), view.projectId());
        return Response.builderSuccess(coreScriptClient.diffVersion(id, v, baseVersion));
    }

    
    @GetMapping("/{id}/flowchart")
    public Response<FlowchartView> flowchart(@PathVariable long id) {
        CaseView view = coreCaseClient.getCase(id);
        guard.assertCanView(UserContext.current(), view.projectId());
        return Response.builderSuccess(coreScriptClient.flowchart(id));
    }

    
    @PutMapping("/{id}/script")
    @RequirePermission("perm:script:script-edit")
    public Response<ScriptView> saveScript(@PathVariable long id,
                                           @RequestParam Integer expectedVersion,
                                           @RequestBody(required = false) SaveScriptRequest request) {
        CaseView view = coreCaseClient.getCase(id);
        guard.assertCanManage(UserContext.current(), view.projectId());
        guard.assertCanWriteCase(UserContext.current(), view.projectId());
        if (request == null || request.schemaVersion() == null || request.schemaVersion().isBlank()) {
            throw new ServiceException("schema 版本不能为空，请在「用例详情」页的「脚本」页签重新保存并携带 schemaVersion（形如 v2）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (request.actionJson() == null || request.actionJson().isBlank()) {
            throw new ServiceException("动作 JSON 不能为空，请在「用例详情」页的「脚本」页签填写动作内容（字段 actionJson）后保存",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (expectedVersion == null) {
            throw new ServiceException("缺少乐观锁版本号（expectedVersion），请刷新「用例详情」页的「脚本」页签取回当前版本后重新保存",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return Response.builderSuccess(coreScriptClient.saveScript(id, request.schemaVersion(),
                request.actionJson(), GENERATED_BY_MANUAL, UserContext.current().userId(),
                expectedVersion));
    }

    
    @PostMapping("/{id}/flowchart/preview")
    public Response<FlowchartView> flowchartPreview(@PathVariable long id,
                                                    @RequestBody(required = false) PreviewFlowchartRequest request) {
        CaseView view = coreCaseClient.getCase(id);
        guard.assertCanView(UserContext.current(), view.projectId());
        return Response.builderSuccess(coreScriptClient.previewFlowchart(id,
                request == null ? null : request.actionJson()));
    }

    
    @PostMapping("/{id}/script/regenerate")
    @RequirePermission("perm:script:to-script")
    public Response<Map<String, Object>> regenerate(@PathVariable long id,
                                                    @RequestParam(defaultValue = "false") boolean force) {
        CaseView view = coreCaseClient.getCase(id);
        guard.assertCanWriteCase(UserContext.current(), view.projectId());
        ScriptView script = coreScriptClient.getScript(id);
        boolean manual = script != null && script.generatedBy() == GENERATED_BY_MANUAL;
        if (manual && !force) {
            return Response.builderSuccess(Map.of("need_confirm", true,
                    "message", "重新生成将覆盖手工编辑内容（当前版本 v" + script.version()
                            + "），确认覆盖请再提交并带 force=true"));
        }
        long taskId = coreScriptClient.regenerateScript(id, view.projectId(), UserContext.current().userId());
        if (manual) {
            coreAuditClient.writeScriptRegenOverwrite(UserContext.current().userId(),
                    view.projectId(), id, script.version(), taskId);
        }
        return Response.builderSuccess(Map.of("taskId", taskId));
    }

    
    @PostMapping("/{id}/trial-run")
    @RequirePermission("perm:script:trial-run")
    public Response<Map<String, Long>> trialRun(@PathVariable long id,
                                                @RequestBody(required = false) TrialRunRequest request) {
        CaseView view = coreCaseClient.getCase(id);
        guard.assertCanWriteCase(UserContext.current(), view.projectId());
        ScriptView script = coreScriptClient.getScript(id);
        if (script == null || script.actionJson() == null || script.actionJson().isBlank()) {
            throw new ServiceException("该用例还没有可执行的脚本，请在「测试用例」打开该用例详情的脚本区点「生成脚本」，完成后再试运行",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (script.status() == 2) {
            throw new ServiceException("脚本处于「待更新」状态（用例已修改），请重新生成后再试运行",
                    BizErrorCode.STATE_MACHINE_REJECT);
        }
        Long envId = request == null ? null : request.envId();
        if (envId == null) {
            throw new ServiceException("请选择执行环境（开发/测试/预发/生产），在「试运行」弹窗的「选择执行环境」下拉中选一项（字段 envId）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        long taskId = coreExecClient.createTask(view.projectId(), id, script.schemaVersion(),
                script.actionJson(), envId, UserContext.current().userId());
        return Response.builderSuccess(Map.of("taskId", taskId));
    }
}
