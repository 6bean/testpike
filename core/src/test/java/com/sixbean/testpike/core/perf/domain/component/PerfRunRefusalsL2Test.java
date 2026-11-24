package com.sixbean.testpike.core.perf.domain.component;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R63 three-element contract for the perf run launch/finish/mark refusals:
 * every sentence echoes the offending submitted value, quotes the on-screen
 * repair entry in 「」 and keeps the original capability clause contiguous so
 * existing assertions on those fragments stay green.
 */
class PerfRunRefusalsL2Test {

    private static void assertGuided(String sentence, String echoedValue, String entry) {
        assertTrue(sentence.contains(echoedValue), "must echo value: " + sentence);
        assertTrue(sentence.contains(entry), "must name entry " + entry + ": " + sentence);
    }

    @Test
    void scenarioMissingEchoesIdAndKeepsLegacyClause() {
        String s = PerfRunRefusals.scenarioMissing(8123);
        assertGuided(s, "8123", "「压测工作台」");
        assertTrue(s.contains("压测场景不存在或不属于该项目"), s);
    }

    @Test
    void envMissingEchoesEnvId() {
        String s = PerfRunRefusals.envMissing(507);
        assertGuided(s, "507", "「环境配置」");
        assertTrue(s.contains("环境不存在"), s);
    }

    @Test
    void envNotInProjectEchoesBothSides() {
        String s = PerfRunRefusals.envNotInProject(507, 33);
        assertGuided(s, "507", "「环境配置」");
        assertTrue(s.contains("33"), s);
        assertTrue(s.contains("不属于该项目"), s);
    }

    @Test
    void gateBlockedKeepsReasonAndPointsToWorkbench() {
        String s = PerfRunRefusals.gateBlocked("目标为生产域名且未加白名单");
        assertGuided(s, "生产域名", "「压测工作台」");
        assertTrue(s.contains("压测运行启动被安全闸拦截："), s);
    }

    @Test
    void durationCeilingEchoesDeclaredAndCeilingAndKey() {
        String s = PerfRunRefusals.durationCeiling("36.5", 24);
        assertTrue(s.contains("36.5") && s.contains("24"), s);
        assertTrue(s.contains("perf.max-duration-hours"), s);
        assertTrue(s.contains("「系统设置」"), s);
    }

    @Test
    void unknownStatusEchoesValueAndListsOptions() {
        String s = PerfRunRefusals.unknownStatus(7);
        assertGuided(s, "7", "「压测运行台账」");
        assertTrue(s.contains("未知运行状态"), s);
        assertTrue(s.contains("1 运行中/2 正常结束/3 熔断停/4 人工停"), s);
    }

    @Test
    void statusNotTerminalEchoesSubmittedValue() {
        String s = PerfRunRefusals.statusNotTerminal(1);
        assertGuided(s, "1", "「压测运行台账」");
        assertTrue(s.contains("结束运行只接受终态"), s);
    }

    @Test
    void finishNotFoundEchoesRunAndProject() {
        String s = PerfRunRefusals.finishNotFound(9001, 33);
        assertGuided(s, "9001", "「压测运行台账」");
        assertTrue(s.contains("压测运行不存在、不属于该项目或已结束"), s);
    }

    @Test
    void unknownMarkEchoesValueAndListsOptions() {
        String s = PerfRunRefusals.unknownMark("GOLD");
        assertGuided(s, "GOLD", "「压测运行台账」");
        assertTrue(s.contains("GOLDEN 金跑/CANDIDATE 候选/VOID 作废"), s);
    }

    @Test
    void markNotFoundEchoesRunId() {
        String s = PerfRunRefusals.markNotFound(9002);
        assertGuided(s, "9002", "「压测运行台账」");
        assertTrue(s.contains("压测运行不存在或不属于该项目"), s);
    }

    @Test
    void markWhileRunningEchoesRunAndStatus() {
        String s = PerfRunRefusals.markWhileRunning(9003, 1);
        assertTrue(s.contains("9003") && s.contains("1"), s);
        assertGuided(s, "9003", "「压测运行台账」");
        assertTrue(s.contains("运行中的压测不可打标"), s);
    }

    @Test
    void snapshotUnserializableNamesNextStep() {
        String s = PerfRunRefusals.snapshotUnserializable();
        assertTrue(s.contains("运行快照序列化失败"), s);
        assertTrue(s.contains("「压测工作台」"), s);
        assertTrue(s.contains("pressure_config"), s);
    }
}
