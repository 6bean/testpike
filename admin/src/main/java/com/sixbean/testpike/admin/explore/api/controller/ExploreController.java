package com.sixbean.testpike.admin.explore.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.LocalRoleMapper;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.explore.api.dto.ExploreCancelView;
import com.sixbean.testpike.admin.explore.api.dto.ExploreCreateInput;
import com.sixbean.testpike.admin.explore.api.dto.ExploreCreateRequest;
import com.sixbean.testpike.admin.explore.api.dto.ExploreCreateResultView;
import com.sixbean.testpike.admin.explore.api.dto.ExploreDetailView;
import com.sixbean.testpike.admin.explore.api.dto.ExploreSessionView;
import com.sixbean.testpike.admin.explore.api.dto.ExploreToCasesRequest;
import com.sixbean.testpike.admin.explore.infrastructure.remote.client.ExploreCoreClient;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;



@RestController
@RequestMapping("/api/v1/explore")
public class ExploreController {

    private final ExploreCoreClient exploreClient;
    private final ProjectAccessGuard accessGuard;

    public ExploreController(ExploreCoreClient exploreClient, ProjectAccessGuard accessGuard) {
        this.exploreClient = exploreClient;
        this.accessGuard = accessGuard;
    }

    
    @GetMapping("/sessions")
    public Response<PageView<ExploreSessionView>> list(@RequestParam long projectId,
                                                       @RequestParam(defaultValue = "1") int page,
                                                       @RequestParam(defaultValue = "20") int size,
                                                       @RequestParam(required = false) String sortBy,
                                                       @RequestParam(required = false) String sortDir) {
        CurrentUser user = UserContext.current();
        accessGuard.assertCanView(user, projectId);
        return Response.builderSuccess(exploreClient.list(projectId, page, size, sortBy, sortDir));
    }

    
    @RequirePermission("perm:script:explore")
    @PostMapping("/sessions")
    public Response<ExploreCreateResultView> create(@RequestBody ExploreCreateRequest request) {
        CurrentUser user = UserContext.current();
        accessGuard.assertCanView(user, request.projectId());
        assertExploreRole(user, request.projectId());
        ExploreCreateInput input = new ExploreCreateInput(
                request.goalText(), request.startUrl(), request.envId(), request.credRef(),
                request.loginStateName());
        if (!Boolean.TRUE.equals(request.confirm())) {
            return Response.builderSuccess(
                    new ExploreCreateResultView(null, exploreClient.estimate(request.projectId(), input)));
        }
        ExploreSessionView session = exploreClient.create(
                request.projectId(), input, UserContext.currentUserId());
        return Response.builderSuccess(new ExploreCreateResultView(session, null));
    }

    
    @GetMapping("/sessions/{id}")
    public Response<ExploreDetailView> detail(@PathVariable long id) {
        ExploreSessionView session = exploreClient.get(id);
        CurrentUser user = UserContext.current();
        accessGuard.assertCanView(user, session.projectId());
        return Response.builderSuccess(
                new ExploreDetailView(session, exploreClient.steps(id)));
    }

    /**
     * Explore session process tail (R62 A-face): the owning project is resolved
     * server-side from the session row and guarded before any ledger row is
     * forwarded, so a non-member never tails another project's exploration run.
     * Paging replays the core cursor window (strictly increasing row id), the
     * same contract as the generation/probe tails — no explore-specific model.
     */
    @GetMapping("/sessions/{id}/process-events")
    public Response<List<com.sixbean.testpike.admin.ai.api.dto.AiTaskEventView>> processEvents(
            @PathVariable long id,
            @RequestParam(defaultValue = "0") long cursor,
            @RequestParam(required = false) Integer limit) {
        ExploreSessionView session = exploreClient.get(id);
        accessGuard.assertCanView(UserContext.current(), session.projectId());
        return Response.builderSuccess(exploreClient.processEvents(id, cursor, limit));
    }

    /**
     * Process-run history revisit door (R67): same membership posture as the
     * cursor window above — the owning project is resolved server-side from
     * the session row and guarded before any ledger aggregate is forwarded.
     * Rows share the AiTaskRunView shape of the probe-runs reference.
     */
    @GetMapping("/sessions/{id}/process-runs")
    public Response<List<com.sixbean.testpike.admin.ai.api.dto.AiTaskRunView>> processRuns(
            @PathVariable long id, @RequestParam(required = false) Integer limit) {
        ExploreSessionView session = exploreClient.get(id);
        accessGuard.assertCanView(UserContext.current(), session.projectId());
        return Response.builderSuccess(exploreClient.processRuns(id, limit));
    }

    
    @GetMapping("/sessions/{id}/steps/{seq}/screenshot")
    public Response<String> screenshot(@PathVariable long id, @PathVariable int seq) {
        ExploreSessionView session = exploreClient.get(id);
        accessGuard.assertCanView(UserContext.current(), session.projectId());
        return Response.builderSuccess(exploreClient.screenshotUrl(id, seq));
    }

    
    @RequirePermission("perm:script:explore")
    @PostMapping("/sessions/{id}/to-cases")
    public Response<List<Long>> toCases(@PathVariable long id,
                                        @RequestBody ExploreToCasesRequest request) {
        ExploreSessionView session = exploreClient.get(id);
        CurrentUser user = UserContext.current();
        accessGuard.assertCanView(user, session.projectId());
        assertExploreRole(user, session.projectId());
        return Response.builderSuccess(
                exploreClient.toCases(id, request, UserContext.currentUserId()));
    }

    
    @DeleteMapping("/sessions/{id}")
    public Response<ExploreCancelView> delete(@PathVariable long id) {
        ExploreSessionView session = exploreClient.get(id);
        CurrentUser user = UserContext.current();
        if (!accessGuard.isAdmin(user)
                && (session.createdBy() == null || session.createdBy() != user.userId())) {
            throw new ServiceException("仅探索会话创建人或系统管理员可取消/清理",
                    BizErrorCode.FORBIDDEN);
        }
        return Response.builderSuccess(exploreClient.cancelSession(id));
    }


    
    private void assertExploreRole(CurrentUser user, long projectId) {
        if (accessGuard.memberRole(user, projectId) >= 2) {
            return;
        }
        if (user.roles() != null && user.roles().contains(LocalRoleMapper.ROLE_TESTER)) {
            return;
        }
        throw new ServiceException("该操作仅项目负责人或测试人员可执行", BizErrorCode.FORBIDDEN);
    }
}
