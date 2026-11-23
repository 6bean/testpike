package com.sixbean.testpike.admin.aichat.api.controller;

import com.sixbean.testpike.admin.aichat.api.dto.MentionCandidateView;
import com.sixbean.testpike.admin.aichat.infrastructure.remote.client.CoreMentionAssemblyClient;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * Mention-candidate door of the dialog composer :
 * the @-palette lists the skills and tools THIS run could actually consume —
 * i.e. the core assembly sets themselves, never a second list and never a
 * browser-side visibility guess.
 * <p>Thin by rule: the H1 project guard runs before any core fetch, the
 * actor id comes from the login context only (the tool door's five-way gate
 * verdict depends on it), and the merge order (skill block first, then tool
 * block, each in the assembly order core returns) is deterministic so the
 * palette never shuffles between two identical opens.
 * <p>Empty candidate sets answer an empty list honestly — a project without
 * published skills and callable tools simply has nothing to mention; the
 * composer degrades to plain text (no popup on an empty palette).
 */
@RestController
@RequestMapping("/api/v1/ai/chat")
public class AiChatMentionController {

    private final CoreMentionAssemblyClient assemblyClient;
    private final ProjectAccessGuard accessGuard;

    public AiChatMentionController(CoreMentionAssemblyClient assemblyClient,
                                   ProjectAccessGuard accessGuard) {
        this.assemblyClient = assemblyClient;
        this.accessGuard = accessGuard;
    }

    /** Skills first, tools second — both slices of the caller's project. */
    @GetMapping("/projects/{projectId}/mention-candidates")
    @RequirePermission("perm:aichat:use")
    public Response<List<MentionCandidateView>> candidates(@PathVariable long projectId) {
        var user = UserContext.current();
        accessGuard.assertCanView(user, projectId);
        List<MentionCandidateView> out = new ArrayList<>();
        assemblyClient.listSkillAssembly(projectId)
                .forEach(row -> out.add(new MentionCandidateView("skill", row.skillId(), row.name())));
        assemblyClient.listToolAssembly(projectId, user.userId())
                .forEach(row -> out.add(new MentionCandidateView("tool", row.toolId(), row.name())));
        return Response.builderSuccess(out);
    }
}
