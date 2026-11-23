package com.sixbean.testpike.core.skill.domain.service;

import com.sixbean.testpike.common.approval.ApprovalStatus;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.skill.api.dto.SkillCreateInput;
import com.sixbean.testpike.core.skill.api.dto.SkillInfo;
import com.sixbean.testpike.core.skill.api.dto.SkillQuery;
import com.sixbean.testpike.core.skill.api.dto.SkillUpdateInput;
import com.sixbean.testpike.core.skill.api.dto.SkillVersionDetail;
import com.sixbean.testpike.core.skill.api.dto.SkillVersionInfo;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import com.sixbean.testpike.core.support.SkillSuiteSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class SkillServiceTest {

    @Autowired
    private SkillService skillService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private SkillSuiteSupport support;

    @BeforeEach
    void prepareServiceSuite() {
        support = new SkillSuiteSupport(jdbcTemplate, List.of("tp_tag_dict"));
        support.begin();
    }

    @AfterEach
    void sweepServiceFixture() {
        support.sweep();
    }


    private SkillCreateInput input(Long projectId, String name, Integer type, String body) {
        return new SkillCreateInput(projectId, name, type, body + " " + support.token(), null, null);
    }

    /** Skill create through the service with R37 teardown tracking. */
    private SkillInfo create(Long operatorId, SkillCreateInput in) {
        SkillInfo info = skillService.create(operatorId, in);
        support.skills().add(info.id());
        return info;
    }

    private int auditCount(String action, long skillId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_audit_log WHERE object_type = 'skill' "
                        + "AND action = ? AND object_id = ?",
                Integer.class, action, String.valueOf(skillId));
        return n == null ? 0 : n;
    }


    @Test
    void 校验四连_name超长_空body_非法type_超大body各400() {
        ServiceException nameTooLong = assertThrows(ServiceException.class,
                () -> create(1L, input(null, "名".repeat(101), 1, "正文")));
        assertEquals(BizErrorCode.VALIDATION_ERROR, nameTooLong.getCode());
        assertTrue(nameTooLong.getMessage().contains("100"), nameTooLong.getMessage());

        // Negative case: blank body must be rejected verbatim, so it bypasses
        // the run-token append the happy-path helper adds.
        ServiceException emptyBody = assertThrows(ServiceException.class,
                () -> create(1L, new SkillCreateInput(null, "合法名", 1, "   ", null, null)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, emptyBody.getCode());
        assertTrue(emptyBody.getMessage().contains("正文"), emptyBody.getMessage());

        ServiceException badType = assertThrows(ServiceException.class,
                () -> create(1L, input(null, "合法名", 6, "正文")));
        assertEquals(BizErrorCode.VALIDATION_ERROR, badType.getCode());
        assertTrue(badType.getMessage().contains("6"), "报错须含非法值: " + badType.getMessage());
        assertTrue(badType.getMessage().contains("用例检查"), "报错须含可选枚举: " + badType.getMessage());

        ServiceException bigBody = assertThrows(ServiceException.class,
                () -> create(1L, input(null, "合法名", 1, "字".repeat(256 * 1024 + 1))));
        assertEquals(BizErrorCode.VALIDATION_ERROR, bigBody.getCode());
        assertTrue(bigBody.getMessage().contains("256"), bigBody.getMessage());

        assertEquals(0, support.countSkills() - support.skillsBefore(), "全部拒绝后零落库");
    }


    @Test
    void 重名40904_系统级桶与项目内_跨项目同名合法() {
        long pidA = support.createProject("SKL-A");
        long pidB = support.createProject("SKL-B");

        SkillInfo first = create(1L, input(null, "支付检查规范", 1, "正文一"));
        assertNotNull(first.id());

        ServiceException sysDup = assertThrows(ServiceException.class,
                () -> create(1L, input(null, "支付检查规范", 2, "正文二")));
        assertEquals(BizErrorCode.RESOURCE_CONFLICT, sysDup.getCode());
        assertTrue(sysDup.getMessage().contains("支付检查规范"), sysDup.getMessage());

        create(1L, input(pidA, "支付检查规范", 1, "正文三"));
        ServiceException projDup = assertThrows(ServiceException.class,
                () -> create(1L, input(pidA, "支付检查规范", 3, "正文四")));
        assertEquals(BizErrorCode.RESOURCE_CONFLICT, projDup.getCode());
        create(1L, input(pidB, "支付检查规范", 1, "正文五"));
        assertEquals(3, support.countSkills() - support.skillsBefore(), "5 次 create 拒 2（两次重名）落 3（系统级+两项目各一）");
    }


    @Test
    void 审批全链_draft到published_版本链快照() {
        SkillInfo created = create(1L, input(null, "动作脚本规范", 3, "动作 JSON 必须显式等待"));
        assertEquals(ApprovalStatus.DRAFT.code(), created.status());
        assertEquals(1, created.currentVersion());
        assertEquals(ApprovalStatus.DRAFT.label(), created.statusLabel());
        assertNull(created.approvedBy());

        SkillInfo submitted = skillService.submit(1L, created.id(), created.version());
        assertEquals(ApprovalStatus.PENDING_APPROVAL.code(), submitted.status());
        assertEquals(1, auditCount("submit", created.id()), "提交落 1 条审计");

        SkillInfo approved = skillService.approve(2L, created.id(), submitted.version());
        assertEquals(ApprovalStatus.PUBLISHED.code(), approved.status());
        assertEquals(2L, approved.approvedBy(), "审批人留痕");
        assertNotNull(approved.approvedAt());
        assertEquals(1, auditCount("approve", created.id()));
        Integer versionApproved = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_skill_version WHERE skill_id = ? AND version = 1 "
                        + "AND approved_at IS NOT NULL", Integer.class, created.id());
        assertEquals(1, versionApproved, "发布时版本链快照 approved_at 回填");
        assertEquals(1, auditCount("create", created.id()));
    }


    @Test
    void 拒绝必填原因_留痕_rejected可重提() {
        SkillInfo created = create(1L, input(null, "需求补齐规范", 2, "正文"));
        SkillInfo submitted = skillService.submit(1L, created.id(), created.version());

        ServiceException noReason = assertThrows(ServiceException.class,
                () -> skillService.reject(2L, created.id(), submitted.version(), "  "));
        assertEquals(BizErrorCode.VALIDATION_ERROR, noReason.getCode());
        assertTrue(noReason.getMessage().contains("原因"), noReason.getMessage());

        SkillInfo rejected = skillService.reject(2L, created.id(), submitted.version(), "术语与现行规范冲突");
        assertEquals(ApprovalStatus.REJECTED.code(), rejected.status());
        assertEquals("术语与现行规范冲突", rejected.rejectReason());

        SkillInfo resubmitted = skillService.submit(1L, rejected.id(), rejected.version());
        assertEquals(ApprovalStatus.PENDING_APPROVAL.code(), resubmitted.status(),
                "S2：rejected 允许 submit 重提");
        assertNull(resubmitted.rejectReason(), "重提清空拒绝原因（新审批轮次）");
    }


    @Test
    void 非法转换_40001人话() {
        SkillInfo draft = create(1L, input(null, "执行规范", 4, "正文"));
        ServiceException e = assertThrows(ServiceException.class,
                () -> skillService.approve(2L, draft.id(), draft.version()));
        assertEquals(BizErrorCode.VALIDATION_ERROR, e.getCode(), "IT-SKL-02 口径=40001 非 40902");
        assertTrue(e.getMessage().contains("草稿") && e.getMessage().contains("不允许"),
                "人话须含当前状态与拒绝语义: " + e.getMessage());
        assertEquals(0, auditCount("approve", draft.id()), "被拒操作零成功审计");
    }


    @Test
    void 重复approve_幂等_状态不变审计不重复() {
        SkillInfo created = create(1L, input(null, "自由技能", 5, "正文"));
        SkillInfo submitted = skillService.submit(1L, created.id(), created.version());
        SkillInfo approved = skillService.approve(2L, created.id(), submitted.version());

        SkillInfo again = skillService.approve(2L, created.id(), approved.version());
        assertEquals(ApprovalStatus.PUBLISHED.code(), again.status(), "第二次仍 200 published");
        assertEquals(approved.version(), again.version(), "幂等短路不得 bump 乐观锁版本");
        assertEquals(1, auditCount("approve", created.id()), "审计不重复");
    }


    @Test
    void 陈旧版本提交_40901人话() {
        SkillInfo created = create(1L, input(null, "并发规范", 1, "正文"));
        SkillInfo submitted = skillService.submit(1L, created.id(), created.version());
        skillService.approve(2L, created.id(), submitted.version());
        ServiceException stale = assertThrows(ServiceException.class,
                () -> skillService.retire(3L, created.id(), submitted.version()));
        assertEquals(BizErrorCode.VERSION_CONFLICT, stale.getCode());
        assertTrue(stale.getMessage().contains("刷新"), stale.getMessage());
        SkillInfo now = skillService.get(created.id());
        assertEquals(ApprovalStatus.PUBLISHED.code(), now.status(), "状态只翻一次");
    }


    @Test
    void 不存在404与retire终态() {
        ServiceException nf = assertThrows(ServiceException.class, () -> skillService.get(999999L));
        assertEquals(BizErrorCode.NOT_FOUND, nf.getCode());

        SkillInfo created = create(1L, input(null, "下架对象", 1, "正文"));
        SkillInfo submitted = skillService.submit(1L, created.id(), created.version());
        SkillInfo approved = skillService.approve(2L, created.id(), submitted.version());
        SkillInfo retired = skillService.retire(2L, created.id(), approved.version());
        assertEquals(ApprovalStatus.RETIRED.code(), retired.status());

        ServiceException terminal = assertThrows(ServiceException.class,
                () -> skillService.submit(1L, created.id(), retired.version()));
        assertTrue(terminal.getMessage().contains("已终止"), "retired 终态引导语: " + terminal.getMessage());
    }


    /** Notifications scoped to THIS fixture skill via its dedup key prefix
     *  ({@code EVENT:skillId:...}) — rows of the factory baseline and of other
     *  suites on the shared dev DB never match (R37: no blanket truncate). */
    private List<Map<String, Object>> notifications(String eventType, long skillId) {
        return jdbcTemplate.queryForList(
                "SELECT user_id, title, content, dedup_key FROM tp_notification "
                        + "WHERE event_type = ? AND dedup_key LIKE ? ORDER BY id",
                eventType, eventType + ":" + skillId + ":%");
    }

    private List<Long> expectedAdminIds() {
        return jdbcTemplate.queryForList(
                "SELECT id FROM tp_user WHERE deleted = 0 AND (role = 4 OR id IN ("
                        + "SELECT ur.user_id FROM tp_user_role ur JOIN tp_role r ON r.id = ur.role_id "
                        + "WHERE r.code = 'admin')) ORDER BY id", Long.class);
    }

    @Test
    void 提交审批_SKILL_APPROVAL_REQUEST通知审批人恰1() {
        List<Long> admins = expectedAdminIds();
        assertFalse(admins.isEmpty(), "dev 库须有在位管理员（AdminAlertRecipients 同源查询）");
        SkillInfo created = create(1L, input(null, "审批提请技能", 1, "正文"));
        skillService.submit(1L, created.id(), created.version());

        List<Map<String, Object>> req = notifications("SKILL_APPROVAL_REQUEST", created.id());
        assertEquals(admins.size(), req.size(), "系统级 SKILL 提交=通知全体管理员各恰 1 条（07 事件表）");
        assertEquals(admins, req.stream().map(r -> ((Number) r.get("user_id")).longValue()).toList(),
                "收件人=全体在位管理员");
        assertTrue(req.get(0).get("title").toString().contains("审批"), req.get(0).get("title").toString());
        assertTrue(req.get(0).get("content").toString().contains("审批提请技能"), "人话含技能名");
    }

    @Test
    void 审批通过_SKILL_APPROVED通知创建人_幂等重复审批不重复通知() {
        SkillInfo created = create(7L, input(null, "过审技能", 1, "正文"));
        SkillInfo submitted = skillService.submit(7L, created.id(), created.version());
        SkillInfo approved = skillService.approve(2L, created.id(), submitted.version());

        List<Map<String, Object>> done = notifications("SKILL_APPROVED", created.id());
        assertEquals(1, done.size());
        assertEquals(7L, ((Number) done.get(0).get("user_id")).longValue(), "收件人=创建人");
        assertTrue(done.get(0).get("content").toString().contains("过审技能"));

        skillService.approve(2L, created.id(), approved.version());
        assertEquals(1, notifications("SKILL_APPROVED", created.id()).size(), "IT-SKL-03 幂等短路不重复通知");
    }

    @Test
    void 审批拒绝_SKILL_APPROVAL_REJECTED含原因通知创建人恰1() {
        SkillInfo created = create(7L, input(null, "被拒技能", 1, "正文"));
        SkillInfo submitted = skillService.submit(7L, created.id(), created.version());
        skillService.reject(2L, created.id(), submitted.version(), "术语与现行规范冲突");

        List<Map<String, Object>> rej = notifications("SKILL_APPROVAL_REJECTED", created.id());
        assertEquals(1, rej.size(), "TC-S-01：拒绝留痕+通知恰 1");
        assertEquals(7L, ((Number) rej.get(0).get("user_id")).longValue());
        assertTrue(rej.get(0).get("content").toString().contains("术语与现行规范冲突"),
                "人话含拒绝原因：" + rej.get(0).get("content"));
    }

    @Test
    void 下架retire_不发审批事件通知() {
        SkillInfo pub = publishedSkill("下架静默技能");
        // ApprovalNotifierSupport.send writes one row per recipient (dedup_key=prefix:userId),
        // so REQUEST volume tracks the live admin set (dynamic baseline, same source as
        // expectedAdminIds used by neighbouring tests); the intent under test is that
        // retire itself adds zero approval-event rows.
        int requestBefore = notifications("SKILL_APPROVAL_REQUEST", pub.id()).size();
        assertEquals(expectedAdminIds().size(), requestBefore, "提交阶段每管理员恰 1 条");
        skillService.retire(2L, pub.id(), pub.version());
        assertEquals(1, notifications("SKILL_APPROVED", pub.id()).size(), "approve 仅创建人 1 条，retire 零新增");
        assertEquals(requestBefore, notifications("SKILL_APPROVAL_REQUEST", pub.id()).size(), "retire 对提请通知零增量");
    }

    @Test
    void 项目级SKILL提交_通知管理员与项目负责人并集去重() {
        long pid = support.createProject("NTF-A");
        jdbcTemplate.update("INSERT INTO tp_user_project (user_id, project_id, role) VALUES (2, ?, 2)", pid);
        SkillInfo created = create(1L, input(pid, "项目提请技能", 1, "正文"));
        skillService.submit(1L, created.id(), created.version());

        List<Map<String, Object>> req = notifications("SKILL_APPROVAL_REQUEST", created.id());
        List<Long> recipients = req.stream().map(r -> ((Number) r.get("user_id")).longValue()).toList();
        assertTrue(recipients.containsAll(expectedAdminIds()), "全体管理员在收件人: " + recipients);
        assertTrue(recipients.contains(2L), "项目负责人在收件人: " + recipients);
        assertEquals(recipients.size(), new java.util.LinkedHashSet<>(recipients).size(), "并集去重");
    }

    @Test
    void 已发布改版重审_再次通知审批人_版本维度的去重键() {
        SkillInfo pub = publishedSkill("改版通知技能");
        int admins = expectedAdminIds().size();
        assertEquals(admins, notifications("SKILL_APPROVAL_REQUEST", pub.id()).size(), "首提=全体管理员各 1 条");
        skillService.update(1L, pub.id(), upd(pub.version(), null, null, "新版正文 v2", null));
        List<Map<String, Object>> req = notifications("SKILL_APPROVAL_REQUEST", pub.id());
        assertEquals(2 * admins, req.size(), "S2 改版自动重审=再通知一轮（v2 去重键不同）");
        assertTrue(req.stream().anyMatch(r -> r.get("content").toString().contains("改版")),
                "重审通知人话标明改版");
    }


    // ==================================================================
    // ==================================================================

    private static SkillUpdateInput upd(int expectedVersion, String name, Integer type,
                                        String body, String frontmatter) {
        return new SkillUpdateInput(expectedVersion, name, type, body, frontmatter);
    }

    private SkillInfo publishedSkill(String name) {
        SkillInfo created = create(1L, input(null, name, 1, "旧版正文"));
        SkillInfo submitted = skillService.submit(1L, created.id(), created.version());
        return skillService.approve(2L, created.id(), submitted.version());
    }

    private int versionRowCount(long skillId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_skill_version WHERE skill_id = ?", Integer.class, skillId);
        return n == null ? 0 : n;
    }


    @Test
    void update草稿_就地更新v1快照_乐观锁推进() {
        SkillInfo created = create(1L, input(null, "草稿修订", 1, "初稿正文"));
        SkillInfo updated = skillService.update(1L, created.id(),
                upd(created.version(), null, null, "修订后正文", null));
        assertTrue(updated.bodyPreview().contains("修订后正文"), updated.bodyPreview());
        assertEquals(1, updated.currentVersion(), "draft 编辑不生新版本");
        assertEquals(created.version() + 1, updated.version(), "乐观锁 version 推进");
        assertEquals(1, versionRowCount(created.id()));
        String snapshot = jdbcTemplate.queryForObject(
                "SELECT body FROM tp_skill_version WHERE skill_id = ? AND version = 1",
                String.class, created.id());
        assertEquals("修订后正文", snapshot, "v1 快照与主表同步就地更新");
        assertEquals(1, auditCount("update", created.id()), "编辑恰 1 条审计");
    }


    @Test
    void update已发布改正文_生v2自动重审_旧版快照保留() {
        SkillInfo pub = publishedSkill("改版重审");
        SkillInfo updated = skillService.update(1L, pub.id(),
                upd(pub.version(), null, null, "新版正文 v2", null));
        assertEquals(ApprovalStatus.PENDING_APPROVAL.code(), updated.status(),
                "S2：已发布改内容自动重审（02「published 改=新版本重审」）");
        assertEquals(2, updated.currentVersion(), "版本链推进到 v2");
        assertEquals(2, versionRowCount(pub.id()));
        String v1Body = jdbcTemplate.queryForObject(
                "SELECT body FROM tp_skill_version WHERE skill_id = ? AND version = 1",
                String.class, pub.id());
        assertEquals("旧版正文 " + support.token(), v1Body, "旧版快照保留（TC-S-02 diff 回滚源）");
        String v2Reason = jdbcTemplate.queryForObject(
                "SELECT reason FROM tp_skill_version WHERE skill_id = ? AND version = 2",
                String.class, pub.id());
        assertNotNull(v2Reason);
        assertTrue(v2Reason.contains("改版"), v2Reason);
        skillService.approve(2L, pub.id(), updated.version());
        Integer v2Approved = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_skill_version WHERE skill_id = ? AND version = 2 "
                        + "AND approved_at IS NOT NULL", Integer.class, pub.id());
        assertEquals(1, v2Approved);
        assertEquals(1, auditCount("update", pub.id()));
    }


    @Test
    void update已发布纯改名_不重审版本链不动() {
        SkillInfo pub = publishedSkill("改名不改审");
        SkillInfo renamed = skillService.update(1L, pub.id(),
                upd(pub.version(), "改名后规范", null, null, null));
        assertEquals(ApprovalStatus.PUBLISHED.code(), renamed.status(), "纯元数据编辑保持 published");
        assertEquals(1, renamed.currentVersion());
        assertEquals(1, versionRowCount(pub.id()), "版本链不生新条目");
        assertEquals("改名后规范", renamed.name());
    }


    @Test
    void update校验_重名_陈旧版本_超大正文_终态拒编辑() {
        create(1L, input(null, "占名技能", 1, "正文"));
        SkillInfo target = create(1L, input(null, "被改技能", 1, "正文"));

        ServiceException dup = assertThrows(ServiceException.class,
                () -> skillService.update(1L, target.id(), upd(target.version(), "占名技能", null, null, null)));
        assertEquals(BizErrorCode.RESOURCE_CONFLICT, dup.getCode());
        assertTrue(dup.getMessage().contains("占名技能"), dup.getMessage());

        ServiceException stale = assertThrows(ServiceException.class,
                () -> skillService.update(1L, target.id(), upd(target.version() + 7, null, null, "改后", null)));
        assertEquals(BizErrorCode.VERSION_CONFLICT, stale.getCode());

        ServiceException big = assertThrows(ServiceException.class,
                () -> skillService.update(1L, target.id(),
                        upd(target.version(), null, null, "字".repeat(256 * 1024 + 1), null)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, big.getCode());

        SkillInfo pub = publishedSkill("终态技能");
        SkillInfo sub2 = skillService.submit(1L, pub.id(), pub.version());
        skillService.approve(2L, pub.id(), sub2.version());
        SkillInfo retired = skillService.retire(2L, pub.id(), skillService.get(pub.id()).version());
        ServiceException terminal = assertThrows(ServiceException.class,
                () -> skillService.update(1L, pub.id(), upd(retired.version(), null, null, "回改", null)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, terminal.getCode(), "retired 终态禁编辑（40001 人话）");
        assertTrue(terminal.getMessage().contains("已终止"), terminal.getMessage());
    }


    @Test
    void list_筛选分页_排序白名单外400人话() {
        long pidA = support.createProject("LST-A");
        create(1L, input(null, "支付检查规范", 1, "关键词甲正文"));
        create(1L, input(pidA, "支付补齐规范", 2, "关键词乙正文"));
        create(1L, input(pidA, "脚本开发规范", 3, "关键词丙"));
        SkillInfo pub = create(1L, input(null, "待发布技能", 5, "正文"));
        skillService.submit(1L, pub.id(), pub.version());

        PageResult<SkillInfo> all = skillService.list(new SkillQuery(
                1, 10, support.token(), null, null, null, null, null, null));
        assertEquals(4L, all.total());

        PageResult<SkillInfo> byKeyword = skillService.list(new SkillQuery(
                1, 10, "支付检查", null, null, null, null, null, null));
        assertEquals(1L, byKeyword.total(), "关键词命中名称");

        PageResult<SkillInfo> byBody = skillService.list(new SkillQuery(
                1, 10, "关键词甲", null, null, null, null, null, null));
        assertEquals(1L, byBody.total(), "关键词命中正文");

        PageResult<SkillInfo> byProject = skillService.list(new SkillQuery(
                1, 10, support.token(), null, null, pidA, null, null, null));
        assertEquals(4L, byProject.total(), "项目筛选=本项目桶∪系统级桶（AR 批默认可见）");

        PageResult<SkillInfo> byType = skillService.list(new SkillQuery(
                1, 10, support.token(), 2, null, null, null, null, null));
        assertEquals(1L, byType.total());

        PageResult<SkillInfo> byStatus = skillService.list(new SkillQuery(
                1, 10, support.token(), null, ApprovalStatus.DRAFT.code(), null, null, null, null));
        assertEquals(3L, byStatus.total());

        PageResult<SkillInfo> paged = skillService.list(new SkillQuery(
                2, 3, support.token(), null, null, null, null, "created_at", "asc"));
        assertEquals(4L, paged.total());
        assertEquals(1, paged.list().size(), "第 2 页剩 1 条");

        PageResult<SkillInfo> clamped = skillService.list(new SkillQuery(
                0, 50, support.token(), null, null, null, null, null, null));
        assertEquals(4, clamped.list().size(), "page=0 钳制为第 1 页零异常");

        ServiceException injected = assertThrows(ServiceException.class,
                () -> skillService.list(new SkillQuery(
                        1, 10, support.token(), null, null, null, null, "name; DROP TABLE tp_skill", null)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, injected.getCode());
        assertTrue(injected.getMessage().contains("白名单"), injected.getMessage());

        PageResult<SkillInfo> sorted = skillService.list(new SkillQuery(
                1, 10, support.token(), null, null, null, null, "id", "asc"));
        assertEquals(4, sorted.list().size());
        assertTrue(sorted.list().get(0).id() < sorted.list().get(3).id(), "id asc 稳定可验");
    }


    @Test
    void list_标签筛选_命中关联表() {
        long pid = support.createProject("TAG-A");
        SkillInfo a = create(1L, input(null, "带标技能", 1, "正文"));
        create(1L, input(null, "无标技能", 1, "正文"));
        long tagId = jdbcTemplate.queryForObject("""
                INSERT INTO tp_tag_dict (project_id, name, type, created_by)
                VALUES (?, '支付域', 5, 1) RETURNING id
                """, Long.class, pid);
        jdbcTemplate.update("INSERT INTO tp_skill_tag (skill_id, tag_id) VALUES (?, ?)",
                a.id(), tagId);

        PageResult<SkillInfo> tagged = skillService.list(new SkillQuery(
                1, 10, null, null, null, null, tagId, null, null));
        assertEquals(1L, tagged.total());
        assertEquals(a.id(), tagged.list().get(0).id());
    }


    @Test
    void versions_列表与快照_不存在404() {
        SkillInfo pub = publishedSkill("版本链技能");
        skillService.update(1L, pub.id(), upd(pub.version(), null, null, "新版正文 v2", null));

        List<SkillVersionInfo> chain = skillService.listVersions(pub.id());
        assertEquals(2, chain.size());
        assertEquals(2, chain.get(0).version(), "列表按版本倒序（新→旧）");
        assertEquals(1, chain.get(1).version());
        assertEquals("旧版正文 " + support.token(), chain.get(1).bodyPreview());

        SkillVersionDetail v1 = skillService.getVersion(pub.id(), 1);
        assertEquals("旧版正文 " + support.token(), v1.body(), "历史快照全量正文（diff 回滚源不截断）");
        SkillVersionDetail v2 = skillService.getVersion(pub.id(), 2);
        assertEquals("新版正文 v2", v2.body());

        ServiceException missing = assertThrows(ServiceException.class,
                () -> skillService.getVersion(pub.id(), 99));
        assertEquals(BizErrorCode.NOT_FOUND, missing.getCode());
    }


    @Test
    void delete_发布态保护_草稿直删级联版本链() {
        long pidBound = support.createProject("DEL-A");
        long pidBound2 = support.createProject("DEL-B");
        SkillInfo bound = create(1L, input(null, "被绑定技能", 1, "正文"));
        SkillInfo b1 = skillService.submit(1L, bound.id(), bound.version());
        SkillInfo b2 = skillService.approve(2L, bound.id(), b1.version());
        jdbcTemplate.update("INSERT INTO tp_project_skill (project_id, skill_id, role) VALUES (?, ?, 3)",
                pidBound, bound.id());
        jdbcTemplate.update("INSERT INTO tp_project_skill (project_id, skill_id, role) VALUES (?, ?, 1)",
                pidBound2, bound.id());

        ServiceException ref = assertThrows(ServiceException.class,
                () -> skillService.delete(1L, bound.id(), b2.version()));
        assertEquals(BizErrorCode.RESOURCE_CONFLICT, ref.getCode());
        assertTrue(ref.getMessage().contains("DEL-A") && ref.getMessage().contains("DEL-B"),
                "409 人话列引用项目清单（IT-SKL-05）: " + ref.getMessage());

        SkillInfo free = publishedSkill("未绑定发布技能");
        ServiceException pubGuard = assertThrows(ServiceException.class,
                () -> skillService.delete(1L, free.id(), free.version()));
        assertEquals(BizErrorCode.VALIDATION_ERROR, pubGuard.getCode());
        assertTrue(pubGuard.getMessage().contains("审批单") || pubGuard.getMessage().contains("下架"),
                pubGuard.getMessage());

        SkillInfo draft = create(1L, input(null, "草稿即删", 1, "正文"));
        skillService.delete(1L, draft.id(), draft.version());
        assertEquals(2, activeAmong(bound.id(), free.id(), draft.id()), "活跃剩 bound + free（rejected 尚未删）");
        assertEquals(1, versionRowCount(draft.id()), "软删保留版本链（还原后历史可 diff）");

        SkillInfo r1 = skillService.submit(1L, free.id(), free.version());
        skillService.reject(2L, free.id(), r1.version(), "撤回改版");
        skillService.delete(1L, free.id(), skillService.get(free.id()).version());
        ServiceException gone = assertThrows(ServiceException.class, () -> skillService.get(free.id()));
        assertEquals(BizErrorCode.NOT_FOUND, gone.getCode());
    }

    /** Active (deleted=0) rows among THIS test's tracked skill ids — the R37 scoped
     *  twin of the former whole-table count (factory rows structurally excluded). */
    private int activeAmong(long... ids) {
        String joined = java.util.Arrays.stream(ids).mapToObj(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_skill WHERE deleted = 0 AND id IN (" + joined + ")",
                Integer.class);
        return n == null ? 0 : n;
    }
}
