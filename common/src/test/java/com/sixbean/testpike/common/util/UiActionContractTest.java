package com.sixbean.testpike.common.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p>Verified: ① thirty-verb frozen baseline (16 phase-one + 10 extensions + 3
 * package-profile + 1 perf assertion), keyed identical to the engine capabilities bitmap;
 */
class UiActionContractTest {

    /** Verb baseline, thirty-one frozen-order entries (mirrors UiActionContract.IR_VERBS). */
    private static final List<String> FROZEN_VERBS = List.of(
            "launch", "close", "tap", "longPress", "doubleTap", "input", "clearInput",
            "swipe", "scroll", "back", "screenshot", "assertVisible", "assertNotVisible",
            "assertText", "waitVisible", "extractText",
            "deepLink", "openUrl", "rotation", "home", "background", "foreground",
            "clearData", "grantPermission", "wakeUnlock", "networkProfile",
            "install", "uninstall", "verifyVersion", "perf", "launchPerf");

    @Test
    void frozenVerbBaselineKeepsOrder() {
        assertEquals(FROZEN_VERBS, UiActionContract.IR_VERBS);
        assertEquals("app.", UiActionContract.APP_NAMESPACE);
    }

    @Test
    void app前缀判定与动词提取() {
        assertTrue(UiActionContract.isAppAction("app.tap"));
        assertFalse(UiActionContract.isAppAction("click"));
        assertFalse(UiActionContract.isAppAction("mobile.tap"));
        assertFalse(UiActionContract.isAppAction(null));
        assertNull(UiActionContract.appActionViolation("click"), "非 app.* 步不归本契约管辖");
        assertNull(UiActionContract.appActionViolation("app.launch"));
    }

    @Test
    void app基线外动词_人话点名全集() {
        String violation = UiActionContract.appActionViolation("app.fly_to_moon");
        assertFalse(violation == null);
        assertTrue(violation.contains("fly_to_moon"), "违例文案必须点名当前动词");
        for (String verb : FROZEN_VERBS) {
            assertTrue(violation.contains(verb), "违例文案必须列出动词基线全集（改错免翻文档）：" + verb);
        }
    }

    @Test
    void 校验器双闸_app合法步放行_非法动词人话拒() {
        ActionJsonValidator validator = new ActionJsonValidator();
        String legal = """
                {"version":"v2","caseId":"TC-IR-001","endType":"app",
                 "steps":[{"action":"app.launch"},{"action":"app.tap"},
                          {"action":"app.assertText"}],
                 "env":{"ENV":"dev"}}
                """;
        assertTrue(validator.validate(legal).passed(), "app.* 基线动词须过 04 白名单闸: "
                + validator.validate(legal).errors());
        String illegal = """
                {"version":"v2","caseId":"TC-IR-002","endType":"app",
                 "steps":[{"action":"app.fly_to_moon"}],
                 "env":{"ENV":"dev"}}
                """;
        ActionCheckResult result = validator.validate(illegal);
        assertFalse(result.passed());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("抽象动作 IR")),
                "违例须为 IR 基线人话文案而非裸未知动作: " + result.errors());
    }

    @Test
    void 双端一致性闸_executor镜像词表逐枚对账() throws IOException {
        Path mirror = repoRoot().resolve("executor/actions_common.py");
        String py = Files.readString(mirror);
        Matcher block = Pattern.compile("IR_VERBS[^=]+=\\s*\\(([^)]*)\\)", Pattern.DOTALL).matcher(py);
        assertTrue(block.find(), "executor/actions_common.py 必须含 IR_VERBS 元组定义（镜像单源）");
        List<String> pyVerbs = new ArrayList<>();
        Matcher token = Pattern.compile("\"([^\"]+)\"").matcher(block.group(1));
        while (token.find()) {
            pyVerbs.add(token.group(1));
        }
        assertEquals(UiActionContract.IR_VERBS, pyVerbs,
                "Java UiActionContract 与 executor/actions_common.py 动词表漂移"
                        + "（词表变更必同批双改，04 §2.2 单源登记）");

        Matcher flags = Pattern.compile("CAPABILITY_FLAGS[^=]+=\\s*frozenset\\(\\{([^}]*)\\}\\)",
                Pattern.DOTALL).matcher(py);
        assertTrue(flags.find(), "executor/actions_common.py 必须含 CAPABILITY_FLAGS 镜像");
        List<String> pyFlags = new ArrayList<>();
        Matcher flagToken = Pattern.compile("\"([^\"]+)\"").matcher(flags.group(1));
        while (flagToken.find()) {
            pyFlags.add(flagToken.group(1));
        }
        assertEquals(UiActionContract.CAPABILITY_FLAGS.stream().sorted().toList(),
                pyFlags.stream().sorted().toList(), "能力特性旗两侧漂移");

        Matcher blacklist = Pattern.compile("EXPLORE_FORBIDDEN_VERBS[^=]+=\\s*frozenset\\(\\{([^}]*)\\}\\)",
                Pattern.DOTALL).matcher(py);
        assertTrue(blacklist.find(), "executor/actions_common.py 必须含 EXPLORE_FORBIDDEN_VERBS 镜像");
        List<String> pyForbidden = new ArrayList<>();
        Matcher verbToken = Pattern.compile("\"([^\"]+)\"").matcher(blacklist.group(1));
        while (verbToken.find()) {
            pyForbidden.add(verbToken.group(1));
        }
        assertEquals(UiActionContract.EXPLORE_FORBIDDEN_VERBS.stream().sorted().toList(),
                pyForbidden.stream().sorted().toList(),
                "探索动作黑名单两侧漂移（词表变更必同批双改，04 §2.2 单源登记）");
    }

    
    private static Path repoRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("executor/actions_common.py"))) {
            p = p.getParent();
        }
        assertTrue(p != null, "未找到 executor/actions_common.py（仓库根定位失败）");
        return p;
    }
}
