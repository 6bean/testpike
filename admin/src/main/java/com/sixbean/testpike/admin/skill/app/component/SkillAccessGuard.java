package com.sixbean.testpike.admin.skill.app.component;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.skill.api.dto.SkillView;
import com.sixbean.testpike.common.exception.BizErrorCode;

import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * <ul>
 * </ul>
 *
 */
@Component
public class SkillAccessGuard {

    private final ProjectAccessGuard projectAccessGuard;

    public SkillAccessGuard(ProjectAccessGuard projectAccessGuard) {
        this.projectAccessGuard = projectAccessGuard;
    }

    
    public void assertCanManage(CurrentUser user, Long projectId) {
        if (projectId == null) {
            if (!projectAccessGuard.isAdmin(user)) {
                throw new ServiceException("系统级 SKILL 仅系统管理员可管理，请联系管理员",
                        BizErrorCode.FORBIDDEN);
            }
            return;
        }
        projectAccessGuard.assertCanManage(user, projectId);
    }

    
    public void assertCanSubmit(CurrentUser user, SkillView skill) {
        if (!Objects.equals(skill.createdBy(), user.userId())) {
            throw new ServiceException("仅 SKILL 创建人可提交审批（当前登录人不是创建人）",
                    BizErrorCode.FORBIDDEN);
        }
    }
}
