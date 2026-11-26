package com.sixbean.testpike.admin.mock.api.controller;

import com.sixbean.testpike.admin.mock.api.dto.MockSpaceCreateRequest;
import com.sixbean.testpike.admin.mock.api.dto.MockSpacePolicyRequest;
import com.sixbean.testpike.admin.mock.api.dto.MockSpaceTransitionRequest;
import com.sixbean.testpike.admin.mock.api.dto.MockSpaceView;
import com.sixbean.testpike.admin.mock.infrastructure.remote.client.CoreMockRuleClient;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
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

/**
 * Mock space admin bridge ( pack C — interface-simulation management
 * surface, first API face of the mock domain).
 * <p>Permission keys from the catalog: list=read (perm:mock:read), create=write
 * (perm:mock:write), transition=publish (perm:mock:publish, ). The
 * traffic-quota key is registered for the later AG pack that opens its face.
 * <p>Thin bridge: every gate (space-key shape, production-env ban, H1 project
 * isolation) is the core save-chain single point — no second check here
 * (red line 6), human-worded errors propagate untouched.
 */
@RestController
@RequestMapping("/api/v1/mock/spaces")
public class MockSpaceController {

    private final CoreMockRuleClient coreMockRuleClient;

    public MockSpaceController(CoreMockRuleClient coreMockRuleClient) {
        this.coreMockRuleClient = coreMockRuleClient;
    }

    /** Spaces of one project in routing order (empty list = honest empty state). */
    @GetMapping
    @RequirePermission("perm:mock:read")
    public Response<List<MockSpaceView>> list(@RequestParam long projectId) {
        return Response.builderSuccess(coreMockRuleClient.listSpaces(projectId));
    }

    /** Create a space (owner travels in the body; core splits validation and persistence). */
    @PostMapping
    @RequirePermission("perm:mock:write")
    public Response<Long> create(@RequestBody MockSpaceCreateRequest request) {
        if (request.projectId() == null) {
            throw new ServiceException(
                    "创建 Mock 空间必须指定所属项目（projectId）——请先在顶栏选择项目，再在「接口仿真」页「新建 Mock 空间」提交", 400);
        }
        return Response.builderSuccess(
                coreMockRuleClient.createSpace(request.projectId(), request.toInput()));
    }

    /**
     * Publish (targetStatus=1) or retire (targetStatus=2) a space. Consumes the
     * APPROVE-family key perm:mock:publish — publishing is a management act, so
     * developers/testers get the human-worded 403 from the permission door, not
     * a silent button. The bridge only guards missing numbers; edge legality,
     * the publish-time production-env re-check and the 40901 optimistic-lock
     * conflict are core's single point (red line 6) and propagate verbatim.
     */
    @PostMapping("/{spaceId}/transition")
    @RequirePermission("perm:mock:publish")
    public Response<MockSpaceView> transition(@PathVariable long spaceId,
                                              @RequestBody MockSpaceTransitionRequest request) {
        if (request.targetStatus() == null) {
            throw new ServiceException("空间状态转换必须指定目标状态（targetStatus：1=发布 / 2=退役）——可在「接口仿真」空间行的「发布」或「退役」动作发起", 400);
        }
        if (request.expectedVersion() == null) {
            throw new ServiceException("空间状态转换必须携带当前版本号（expectedVersion，来自列表行），"
                    + "请刷新后重试（版本号见「接口仿真」空间列表行，刷新页面即可取到最新值）", 400);
        }
        return Response.builderSuccess(coreMockRuleClient.transitionSpace(
                spaceId, request.targetStatus(), request.expectedVersion()));
    }

    /**
 * Save the space's miss ladder : what an unmatched request
 * does — passthrough forwards to the bound target, reject answers the plain
 * 404, llm-fallback runs the derived ladder with the gated AI rung.
 * Consumes the EDIT key perm:mock:write (configuration act, same family as
 * space create). The bridge only guards missing numbers; rung-name legality
 * lives in the shared MockMissPolicies spectrum inside core's policy door
 * and the 40901 optimistic-lock conflict propagates verbatim (red line 6).
 */
    @PutMapping("/{spaceId}/policy")
    @RequirePermission("perm:mock:write")
    public Response<MockSpaceView> updatePolicy(@PathVariable long spaceId,
                                                @RequestBody MockSpacePolicyRequest request) {
        if (request.missPolicy() == null || request.missPolicy().isBlank()) {
            throw new ServiceException("保存未命中策略必须指定档位（missPolicy："
                    + "passthrough=透传 / reject=拒绝 / llm-fallback=AI 兜底）——请在「接口仿真」空间详情的「未命中策略」区选择档位后保存", 400);
        }
        if (request.expectedVersion() == null) {
            throw new ServiceException("保存未命中策略必须携带当前版本号（expectedVersion，来自列表行），"
                    + "请刷新后重试（版本号见「接口仿真」空间列表行，刷新页面即可取到最新值）", 400);
        }
        return Response.builderSuccess(coreMockRuleClient.updateMissPolicy(
                spaceId, request.missPolicy(), request.expectedVersion()));
    }
}
