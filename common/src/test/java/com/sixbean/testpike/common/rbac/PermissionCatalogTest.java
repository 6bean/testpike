package com.sixbean.testpike.common.rbac;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class PermissionCatalogTest {

 /** Key format: perm:{domain}:{action} or two-segment-resource forms
 * perm:{domain}:{resource}:{action} (appauto key family, frozen shape) and the
 * four-segment cluster candidate family perm:cluster:release:candidate:{action}
 * (key names frozen verbatim by the AF ruling doc). */
    private static final Pattern KEY_PATTERN =
            Pattern.compile("^perm:[a-z]+:[a-z][a-z0-9-]*(:[a-z][a-z0-9-]*){0,2}$");

    @Test
    void catalogCoversAllFunctionPointsWithV11() {
        assertEquals(210, PermissionCatalog.values().length,
                "K12 权限键=147 基线 + V11/V14/C4 三新键 + 引擎/平台描述符目录六键"
                        + " + 环境档案双键 + 录制转流程发起键一枚（perm:appauto:record，"
                        + "§14.6 冻结清单本包首消费）+ 小程序 mock 智辅一枚"
                        + "（perm:appauto:wxmock:run，§3-A6 三段键族）"
                        + " + 升级顾问 AI 消耗闸一枚（perm:appauto:advisor:run，§11.4 U-adv）"
                        + " + 机型矩阵扇出消耗闸一枚（perm:appauto:matrix:run）"
                        + " + 凭据保险箱双键（perm:appauto:cred:view/manage）"
                        + " + 包档案双键（perm:appauto:pkg:view/manage）"
                        + " + 性能曲线查看一枚（perm:appauto:perf:view，§14.6 G5 T329 消费位）"
                        + " + 装机入池与机群七枚（perm:cluster:*，§14.6 冻结清单 cluster 侧全量）"
                        + " + 设备接管操作发起一枚（perm:appauto:watch:takeover，§14.6 G5 watch:takeover T332 消费位）"
                        + " + 接口仿真四枚（perm:mock:read/write/publish/traffic，AG 裁决键族 T357 入册）"
                        + " + 在线 AI 对话门键一枚（perm:aichat:use，AO 裁决键族 T426 入册）"
                        + " + 共享资产池门键四枚（perm:asset:pool:read/publish/withdraw/pin，"
                        + "Y 波 T288 裁决键族入册）"
                        + " + 项目记忆账本门键一枚（perm:aichat:project-memory，AP T449 裁决键族入册）"
                        + " + 死信台账三键（perm:system:dead-letter-read/replay/void，AJ 批 T383/T384 入册）"
                        + " + 评测门面五键（perm:judge:*，AW 批 T596 入册，含 M1 迁移目标 run:view）"
                        + " + 登录态账本六键（perm:session:capture/read/manage/verify/consume/exempt，AV 批 T600 入册）"
                        + "（perm:exec:task-cancel + perm:comment:use + perm:review:auto-assign"
                        + " + appauto 设备/引擎/平台 view|manage 六枚 + profile view|manage 两枚，"
                        + "缺一多一均为契约破坏）");
        assertTrue(PermissionCatalog.exists("perm:mock:read"),
                "接口仿真查看键必须在目录内（AG 裁决四键键族；T357 包C 入册）");
        assertTrue(PermissionCatalog.exists("perm:mock:write"),
                "接口仿真编辑键必须在目录内（AG 裁决四键键族；T357 包C 入册）");
        assertTrue(PermissionCatalog.exists("perm:mock:publish"),
                "接口仿真发布/退役键必须在目录内（AG 裁决四键键族；随 T359 消费位）");
        assertTrue(PermissionCatalog.exists("perm:mock:traffic"),
                "接口仿真流量配额与审计归属键必须在目录内（AG 裁决四键键族；随 T362/T363 消费位）");
        assertTrue(PermissionCatalog.exists("perm:aichat:use"),
                "在线 AI 对话门键必须在目录内（AO 裁决键族；T426 入册，VIEW 族=成员恒持，隔离仍由 core H1 守卫单源）");
        assertTrue(PermissionCatalog.exists("perm:aichat:project-memory"),
                "项目记忆账本门键必须在目录内（AP T449 裁决件§七a；VIEW 族=成员恒持，管理动作仍由项目角色闸单源）");
        assertTrue(PermissionCatalog.exists("perm:appauto:advisor:run"),
                "升级顾问消耗闸键必须在目录内（§11.4 U-adv AI 键族；T322 包③）");
        assertTrue(PermissionCatalog.exists("perm:appauto:perf:view"),
                "移动端性能查看键必须在目录内（§14.6 G5 清单 perf:view；T329 包④入册）");
        assertTrue(PermissionCatalog.exists("perm:appauto:explore:run"),
                "App 探索发起键必须在目录内（§14.6 G5 清单 explore:run；T330 包②入册，计数 176→177）");
        assertTrue(PermissionCatalog.exists("perm:appauto:watch:takeover"),
                "设备接管操作发起键必须在目录内（§14.6 G5 清单 watch:takeover；T332 包①入册，计数 177→178）");
        assertTrue(PermissionCatalog.exists("perm:appauto:reservation:view"),
                "设备预约查看键必须在目录内（§48 N1 预约三段键族；T339 片③入册，计数 178→181）");
        assertTrue(PermissionCatalog.exists("perm:appauto:reservation:create"),
                "设备预约创建/归还/取消键必须在目录内（§48 N1；T339 片③入册）");
        assertTrue(PermissionCatalog.exists("perm:appauto:reservation:approve"),
                "设备预约审批键必须在目录内（§48 N1 审批开关开态的批准/驳回闸；T339 片③入册）");
        assertTrue(PermissionCatalog.exists("perm:cluster:install:run"),
                "装机入池令牌签发键必须在目录内（AB 波冻结 §14.6 一字不差；T315 包②）");
        assertTrue(PermissionCatalog.exists("perm:comment:use"),
                "TC-V14-01 依赖锚点键 perm:comment:use 必须在目录内（V14 评论与附件，M7 T240）");
        assertTrue(PermissionCatalog.exists("perm:appauto:engine:view"),
                "引擎目录只读键必须在目录内（AB 波冻结 §14.6 一字不差）");
        assertTrue(PermissionCatalog.exists("perm:appauto:engine:manage"),
                "引擎目录管理键必须在目录内");
        assertTrue(PermissionCatalog.exists("perm:appauto:platform:view"),
                "平台目录只读键必须在目录内");
        assertTrue(PermissionCatalog.exists("perm:appauto:platform:manage"),
                "平台目录管理键必须在目录内");
        assertTrue(PermissionCatalog.exists("perm:appauto:device:view"),
                "设备查看键补录在册（§14.6 冻结清单；闸切换随后续收口包，本片仅登记）");
        assertTrue(PermissionCatalog.exists("perm:appauto:device:manage"),
                "设备管理键补录在册");
        assertTrue(PermissionCatalog.exists("perm:session:capture"),
                "登录态采集发起键必须在目录内（AV1；T600 入册）");
        assertTrue(PermissionCatalog.exists("perm:session:read"),
                "登录态账本查看键必须在目录内（AV2 读半；T600 入册，门面叶权限域=读键）");
        assertTrue(PermissionCatalog.exists("perm:session:manage"),
                "登录态停用/续期/删除键必须在目录内（AV2 管半；T600 入册）");
        assertTrue(PermissionCatalog.exists("perm:session:verify"),
                "登录态重放核验键必须在目录内（AV3 三触发面共门；T600 入册）");
        assertTrue(PermissionCatalog.exists("perm:session:consume"),
                "登录态消费引用键必须在目录内（AV4 三通路共门；T600 入册）");
        assertTrue(PermissionCatalog.exists("perm:session:exempt"),
                "验证码豁免声明键必须在目录内（AV5 登记+复核面，非绕过承诺；T600 入册）");
    }

    @Test
    void 三段键格式在册_引擎平台目录族() {
        for (PermissionCatalog p : PermissionCatalog.values()) {
            if (!p.key().startsWith("perm:appauto:")) {
                continue;
            }
            String[] seg = p.key().split(":");
            assertTrue(seg.length == 3 || seg.length == 4,
                    "appauto 键必须二/三段式: " + p.key());
            assertEquals("appauto", seg[1], "域 token 必须=PermissionDomain.APPAUTO: " + p.key());
            assertEquals(PermissionDomain.APPAUTO, p.domain(), "域分组必须=APPAUTO: " + p.key());
            assertNotNull(p.family(), "动作族缺失: " + p.key());
        }
        assertTrue(PermissionCatalog.exists("perm:appauto:record"),
                "录制转流程发起键在册（§14.6 冻结清单动作级二枚段形态，T309 包④首消费）");
        assertTrue(PermissionCatalog.exists("perm:appauto:wxmock:run"),
                "小程序 mock 智辅生成键在册（§3-A6 三段键族 {资源}:{动作} 形态，T309 包⑥c 消费）");
    }

    @Test
    void keysWellFormedAndUnique() {
        Set<String> seen = new HashSet<>();
        for (PermissionCatalog p : PermissionCatalog.values()) {
            assertTrue(KEY_PATTERN.matcher(p.key()).matches(),
                    "键格式必须 perm:{域}:{动作}，实际=" + p.key());
            assertTrue(seen.add(p.key()), "权限键重复: " + p.key());
            assertNotNull(p.label(), "中文名缺失: " + p.key());
            assertNotNull(p.functionId(), "RTM 功能点编号缺失: " + p.key());
            assertNotNull(p.domain(), "域分组缺失: " + p.key());
            assertNotNull(p.family(), "动作族缺失: " + p.key());
        }
    }

    @Test
    void functionIdsUnique() {
        Set<String> ids = new HashSet<>();
        for (PermissionCatalog p : PermissionCatalog.values()) {
            assertTrue(ids.add(p.functionId()), "功能点编号重复映射: " + p.functionId());
        }
        assertEquals(PermissionCatalog.values().length, ids.size());
    }

    @Test
    void groupedByDomainMatchesEnumDomains() {
        Map<PermissionDomain, List<PermissionCatalog>> grouped = PermissionCatalog.groupedByDomain();
        assertEquals(PermissionDomain.values().length, grouped.size(),
                "域分组树必须覆盖 PermissionDomain 全部枚举值");
        int total = grouped.values().stream().mapToInt(List::size).sum();
        assertEquals(PermissionCatalog.values().length, total, "分组并集必须等于全目录（零漏项零重复）");
        assertTrue(grouped.keySet().stream().allMatch(d -> !grouped.get(d).isEmpty()),
                "不允许空域分组");
    }

    @Test
    void skillApproveKeyAnchoredToTcK12_01() {
        PermissionCatalog p = PermissionCatalog.fromKey("perm:skill:approve");
        assertNotNull(p, "TC-K12-01 依赖锚点键 perm:skill:approve 必须在目录内");
        assertEquals("S2", p.functionId());
        assertEquals(PermActionFamily.APPROVE, p.family());
        assertTrue(PermissionCatalog.exists("perm:skill:approve"));
        assertTrue(!PermissionCatalog.exists("perm:not:exist"), "未知键 exists 必须 false");
        assertTrue(!PermissionCatalog.exists(null));
    }

    @Test
    void allKeysViewIsCatalogOrderSnapshot() {
        List<String> keys = PermissionCatalog.allKeys();
        assertEquals(PermissionCatalog.values().length, keys.size());
        assertEquals(new HashSet<>(keys).size(), keys.size(), "allKeys 不允许重复");
        assertTrue(keys.contains("perm:skill:approve"));
    }
}
