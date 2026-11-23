package com.sixbean.testpike.core.cost.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.common.domain.component.AfterCommitExecutor;
import com.sixbean.testpike.core.cost.api.dto.CostWaterView;
import com.sixbean.testpike.core.cost.domain.service.CostService;
import com.sixbean.testpike.core.notification.api.dto.NotificationCreateInput;
import com.sixbean.testpike.core.notification.domain.service.NotificationService;
import com.sixbean.testpike.core.project.api.dto.ProjectInfo;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.common.domain.component.ProjectOwners;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;


@Component
public class CostBreakGuard {

    private static final Logger log = LoggerFactory.getLogger(CostBreakGuard.class);

    
    static final String EVENT_ALERT = "COST_BUDGET_ALERT";

    private final CostService costService;
    private final ProjectService projectService;
    private final ProjectOwners projectOwners;
    private final NotificationService notificationService;
    private final AfterCommitExecutor afterCommitExecutor;

    public CostBreakGuard(CostService costService, ProjectService projectService,
                          ProjectOwners projectOwners, NotificationService notificationService,
                          AfterCommitExecutor afterCommitExecutor) {
        this.costService = costService;
        this.projectService = projectService;
        this.projectOwners = projectOwners;
        this.notificationService = notificationService;
        this.afterCommitExecutor = afterCommitExecutor;
    }

    
    public void assertGenerationAllowed(long projectId) {
        CostWaterView water = costService.waterLevel(projectId);
        if (water.budgetCents() <= 0) {
            return;
        }
        boolean reachedAlert = water.costCents() * 100 >= water.budgetCents() * water.alertPct();
        boolean exhausted = water.costCents() >= water.budgetCents();
        if (reachedAlert) {
            afterCommitExecutor.execute(() -> sendAlertQuietly(projectId, water));
        }
        if (exhausted && water.breakEnabled() && !water.overrideActive()) {
            throw new ServiceException("本月 Token 预算已用尽（"
                    + CostWaterView.yuan(water.costCents()) + "/" + CostWaterView.yuan(water.budgetCents())
                    + "），AI 生成已暂停，请管理员评估后恢复（成本看板可临时恢复）",
                    BizErrorCode.COST_BUDGET_EXCEEDED);
        }
    }

    
    private void sendAlertQuietly(long projectId, CostWaterView water) {
        try {
            ProjectInfo project = projectService.get(projectId);
            String day = LocalDate.now().toString();
            String content = "项目「" + (project == null ? projectId : project.name()) + "」本月 Token 成本已达预警水位 "
                    + water.alertPct() + "%（" + CostWaterView.yuan(water.costCents())
                    + "/" + CostWaterView.yuan(water.budgetCents()) + "），达 100% 将暂停 AI 生成，请评估预算。";
            for (Long userId : projectOwners.resolveWithCreator(projectId)) {
                notificationService.create(new NotificationCreateInput(userId, projectId, EVENT_ALERT,
                        "成本预算预警：" + water.alertPct() + "% 水位", content,
                        EVENT_ALERT + ":" + projectId + ":" + day + ":" + userId));
            }
        } catch (RuntimeException e) {
            log.warn("COST_BUDGET_ALERT 发送失败（projectId={}）: {}", projectId, e.getMessage());
        }
    }
}
