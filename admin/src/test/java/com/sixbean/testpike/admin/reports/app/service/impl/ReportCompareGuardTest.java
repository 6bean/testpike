package com.sixbean.testpike.admin.reports.app.service.impl;

import com.sixbean.testpike.admin.audit.api.dto.AccessDeniedAuditRequest;
import com.sixbean.testpike.admin.audit.infrastructure.remote.client.StubCoreAuditClient;
import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.project.infrastructure.remote.client.StubCoreProjectClient;
import com.sixbean.testpike.admin.reports.api.dto.CompareItemView;
import com.sixbean.testpike.admin.reports.infrastructure.remote.client.StubCoreReportClient;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class ReportCompareGuardTest {

    private final StubCoreProjectClient projectClient = new StubCoreProjectClient();
    private final ProjectAccessGuard guard = new ProjectAccessGuard(projectClient);
    private final StubCoreReportClient reportClient = new StubCoreReportClient();
    private final StubCoreAuditClient auditClient = new StubCoreAuditClient();
    private final ReportCompareServiceImpl service =
            new ReportCompareServiceImpl(guard, reportClient, auditClient);

    private static final CurrentUser TESTER =
            new CurrentUser(1L, "u", "U", "D", List.of("TESTER"), System.currentTimeMillis() + 3600_000, null);
    private static final CurrentUser ADMIN =
            new CurrentUser(2L, "a", "A", "D", List.of("ADMIN"), System.currentTimeMillis() + 3600_000, null);

    @Test
    void 全部可见_桥接透传_无审计() {
        projectClient.memberRoleResult(1);
        service.compare(TESTER, List.of(1L, 2L));
        assertEquals(List.of(1L, 2L), reportClient.requested, "全部可见 → 原样透传 core 桥接");
        assertTrue(auditClient.denials.isEmpty(), "无越权不得产审计行");
    }

    @Test
    void 任一越权_整体403_禁部分返回() {
        projectClient.memberRoleResult(1);
        projectClient.crossProject(3L);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.compare(TESTER, List.of(1L, 3L)),
                "P3 越权 → 整体 403（TC-H6-02，禁返回 P1 部分结果）");
        assertEquals(BizErrorCode.FORBIDDEN, ex.getCode());
        assertTrue(ex.getMessage().contains("3"), "人话点名越权项目: " + ex.getMessage());
        assertTrue(reportClient.requested.isEmpty(), "越权时 core 桥接不得被调用（禁部分返回）");
    }

    @Test
    void 越权尝试_审计留痕_ACCESS_DENIED() {
        projectClient.memberRoleResult(1);
        projectClient.crossProject(3L);
        assertThrows(ServiceException.class, () -> service.compare(TESTER, List.of(1L, 3L)));
        assertEquals(1, auditClient.denials.size(), "越权尝试必须记审计（TC-H6-02）");
        AccessDeniedAuditRequest entry = auditClient.denials.get(0);
        assertEquals("report", entry.objectType());
        assertEquals("ACCESS_DENIED", entry.action());
        assertEquals("compare", entry.objectId());
        assertEquals(1L, entry.operatorId(), "审计记操作人");
        assertEquals(List.of(1L, 3L), entry.before().get("attempted"), "快照含全部尝试项目");
        assertEquals(3L, ((Number) entry.before().get("denied")).longValue(), "快照点名首个越权项");
    }

    @Test
    void 超上限_400人话_前置拒绝() {
        List<Long> tooMany = new ArrayList<>();
        for (long i = 1; i <= 21; i++) {
            tooMany.add(i);
        }
        ServiceException ex = assertThrows(ServiceException.class, () -> service.compare(ADMIN, tooMany),
                "IT-RPT-01：admin 侧前置 400（core 兜底仍有）");
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("20"), "人话含上限: " + ex.getMessage());
        assertTrue(reportClient.requested.isEmpty());
    }

    @Test
    void 空列表_400人话() {
        ServiceException ex = assertThrows(ServiceException.class, () -> service.compare(ADMIN, List.of()));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(reportClient.requested.isEmpty());
    }

    @Test
    void 审计写失败_不吞403_仅告警() {
        projectClient.memberRoleResult(1);
        projectClient.crossProject(3L);
        auditClient.fail = true;
        ServiceException ex = assertThrows(ServiceException.class, () -> service.compare(TESTER, List.of(1L, 3L)),
                "审计缺写不影响 403 判定（E7 writeAudit 同基径）");
        assertEquals(BizErrorCode.FORBIDDEN, ex.getCode());
    }

    
    @Test
    void 全部可见_结果原样透传() {
        projectClient.memberRoleResult(1);
        List<CompareItemView> items = service.compare(TESTER, List.of(1L));
        assertTrue(items.isEmpty(), "桩返回空 → 原样透传空（compareOne 明细由 core L2/L3 覆盖）");
    }
}
