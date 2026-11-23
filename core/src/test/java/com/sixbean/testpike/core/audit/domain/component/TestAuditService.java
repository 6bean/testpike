package com.sixbean.testpike.core.audit.domain.component;

import com.sixbean.testpike.core.audit.domain.component.Audit;


import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;


@Service
public class TestAuditService {

    @Audit(objectType = "case", action = "review", objectId = "#id",
            before = "#before", after = "#after")
    public String reviewCase(Long id, Map<String, Object> before, Map<String, Object> after) {
        return "ok-" + id;
    }

    @Audit(objectType = "case", action = "review", objectId = "#id")
    public void failCase(Long id) {
        throw new ServiceException("评审失败: 用例 %s 不存在", 40401, id);
    }

    // Fixture-exclusive object_type/action family ("fx-plan"): the shared audit
    // ledger keeps rows across runs, so this fixture never writes under the
    // production "plan" type and its rows stay identifiable on the ledger.
    @Audit(objectType = "fx-plan", action = "fx_plan_create", objectId = "#result.id")
    public long failWithResultId() {
        throw new ServiceException("计划创建失败（模拟）", 40001);
    }

 
    @Audit(objectType = "llm_config", action = "update", objectId = "#id")
    public String updateWithContext(Long id, Map<String, Object> after) {
        AuditLogContext.setBefore(AuditSnapshot.masked(
                AuditSnapshot.of(Map.of("name", "旧模型", "apiKey", "sk-old-123"))));
        return "ok-" + id;
    }

 
    @Audit(objectType = "sys_config", action = "update", objectId = "#id")
    public String failWithContext(Long id) {
        AuditLogContext.setBefore(AuditSnapshot.masked(
                AuditSnapshot.of(Map.of("configValue", "旧值"))));
        throw new ServiceException("配置更新失败（模拟）", 40002);
    }

 
    @Audit(objectType = "case", action = "create", objectId = "#result.count", after = "#result")
    public NestedCaseSnapshot nestedCreate(Integer count) {
        return new NestedCaseSnapshot("z-后声明的字段", "a-先声明的字段",
                LocalDateTime.of(2026, 9, 1, 12, 5, 23), count);
    }
}
