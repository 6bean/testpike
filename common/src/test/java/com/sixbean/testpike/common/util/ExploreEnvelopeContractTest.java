package com.sixbean.testpike.common.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class ExploreEnvelopeContractTest {

    private static final Path WORKER_PY = repoRoot().resolve("executor/explore_worker.py");

    
    private static Path repoRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("executor/explore_worker.py"))) {
            p = p.getParent();
        }
        assertTrue(p != null, "仓库根定位失败（executor/explore_worker.py 不可达=对账锚丢失）");
        return p;
    }

    @Test
    void 移动漫游白名单五枚冻结序() {
        assertEquals(List.of("tree-snapshot", "tap", "back", "swipe", "home"),
                ExploreEnvelopeContract.MOBILE_ROAM_ACTIONS);
        assertTrue(ExploreEnvelopeContract.isMobileRoamAction("tap"));
        assertFalse(ExploreEnvelopeContract.isMobileRoamAction("uninstall"),
                "黑名单动词必不在白名单（探索装配侧过滤面，IR_VERBS 值域不动=坑②）");
    }

    @Test
    void 移动形态判定_前缀与信封双键() {
        assertTrue(ExploreEnvelopeContract.isAppStartUrl(" app://com.demo.shop"));
        assertFalse(ExploreEnvelopeContract.isAppStartUrl("http://a/b"));
        assertFalse(ExploreEnvelopeContract.isAppStartUrl(null));
        assertTrue(ExploreEnvelopeContract.isMobileEnvelope("explore-act", "mobile"));
        assertFalse(ExploreEnvelopeContract.isMobileEnvelope("explore-act", null),
                "web 形态零冲击=target 缺位");
    }

    @Test
    void 载体步映射与越界点名() {
        assertEquals("app.screenshot",
                ExploreEnvelopeContract.roamToIrStepAction("tree-snapshot"),
                "树观察=载体只读步（零新 IR 动词）");
        assertEquals("app.tap", ExploreEnvelopeContract.roamToIrStepAction("tap"));
        assertNull(ExploreEnvelopeContract.mobileRoamViolation("swipe"));
        String violation = ExploreEnvelopeContract.mobileRoamViolation("clearData");
        assertTrue(violation.contains("clearData") && violation.contains("漫游白名单"),
                "白名单外人话点名，实际=" + violation);
    }

    
    @Test
    void java白名单与executor_py逐字同集合() throws Exception {
        String py = Files.readString(WORKER_PY);
        assertEquals(pyTuple("MOBILE_ROAM_ACTIONS"),
                ExploreEnvelopeContract.MOBILE_ROAM_ACTIONS);
        assertEquals(pyLiteral("ENVELOPE_KIND"), ExploreEnvelopeContract.EXPLORE_ACT_KIND);
        assertEquals(pyLiteral("MOBILE_TARGET"), ExploreEnvelopeContract.TARGET_MOBILE);
        assertEquals(pyLiteral("TREE_CARRIER_STEP"), ExploreEnvelopeContract.TREE_CARRIER_STEP);
    }

    private static List<String> pyTuple(String name) throws Exception {
        Matcher m = Pattern.compile(name + " = \\(([^)]*)\\)", Pattern.DOTALL)
                .matcher(Files.readString(WORKER_PY));
        assertTrue(m.find(), name + " 元组在 explore_worker.py 未找到（对账锚丢失=先修锚）");
        return List.of(m.group(1).replaceAll("\\s+", "").replace("\"", "").split(","));
    }

    private static String pyLiteral(String name) throws Exception {
        Matcher m = Pattern.compile(name + " = \"([^\"]*)\"")
                .matcher(Files.readString(WORKER_PY));
        assertTrue(m.find(), name + " 字面量在 explore_worker.py 未找到（对账锚丢失=先修锚）");
        return m.group(1);
    }
}
