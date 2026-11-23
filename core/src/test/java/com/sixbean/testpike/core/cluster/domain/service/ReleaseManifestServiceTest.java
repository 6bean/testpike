package com.sixbean.testpike.core.cluster.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cluster.api.dto.ReleaseManifestInfo;
import com.sixbean.testpike.core.cluster.api.dto.ReleaseRegisterInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class ReleaseManifestServiceTest {

    @Autowired
    private ReleaseManifestService manifestService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanManifest() {
        jdbcTemplate.execute("DELETE FROM tp_release_manifest");
    }

    private static ReleaseRegisterInput sample(String version) {
        return new ReleaseRegisterInput(
                version,
                "[{\"component\":\"executor-agent\",\"support\":\">=1.3\",\"pinned\":\"1.4.2\"}]",
                "[\"Mobilewright 0.1.x 起定位语义变更：自定义定位器需重录校准\"]",
                "1.3.0", "1.5.0",
                "跨版本升级须先到 1.3 再升本版本（1.2 及以下禁直跳）",
                "manual",
                // supply-chain gate: a pinned family must ship its artifact.
                "[{\"component_family\":\"executor-agent\",\"url\":"
                        + "\"https://github.com/testpike/releases/executor-agent-1.4.2.zip\","
                        + "\"sha256\":\"" + "a".repeat(64) + "\",\"size\":2048}]",
                null);
    }

    @Test
    void register_thenLatest_roundTrip() {
        ReleaseManifestInfo saved = manifestService.register(sample("1.4.0"));
        assertNotNull(saved.id(), "登记必须回读落库行");
        assertNotNull(saved.registeredAt(), "登记时间落库");

        ReleaseManifestInfo latest = manifestService.latest();
        assertEquals("1.4.0", latest.version());
        assertTrue(latest.componentMatrix().contains("executor-agent"), "部件矩阵透传：" + latest.componentMatrix());
        assertTrue(latest.breakingChanges().contains("定位语义变更"), "破坏性变更人话逐条透传");
        assertEquals("1.3.0", latest.executorVersionMin());
        assertEquals("1.5.0", latest.executorVersionMax());
        assertTrue(latest.suggestedPath().contains("禁直跳"), "建议升级路径（跳版限制）透传");
    }

    @Test
    void reRegister_sameVersion_overwritesSingleRow() {
        manifestService.register(sample("1.4.0"));
        manifestService.register(sample("1.4.0"));
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_release_manifest WHERE version = '1.4.0'", Integer.class);
        assertEquals(1, rows, "版本唯一行，重登=覆盖禁第二行（幂等）");
    }

    @Test
    void blankVersion_rejectedWithHumanMessage() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> manifestService.register(sample("  ")));
        assertTrue(e.getMessage().contains("版本号"), e.getMessage());
    }

    @Test
    void rangeLowerAboveUpper_rejected() {
        ReleaseRegisterInput bad = new ReleaseRegisterInput("1.4.0",
                "[]", "[]", "1.6.0", "1.3.0", null, "manual", null, null);
        ServiceException e = assertThrows(ServiceException.class, () -> manifestService.register(bad));
        assertTrue(e.getMessage().contains("下界") && e.getMessage().contains("上界"),
                "人话须点名下界大于上界：" + e.getMessage());
    }

    @Test
    void breakingChanges_mustBeArrayWithHumanSentences() {
        ServiceException notArray = assertThrows(ServiceException.class,
                () -> manifestService.register(new ReleaseRegisterInput("1.4.1",
                        "[]", "见公告", null, null, null, "manual", null, null)));
        assertTrue(notArray.getMessage().contains("破坏性变更"), notArray.getMessage());

        ServiceException blankItem = assertThrows(ServiceException.class,
                () -> manifestService.register(new ReleaseRegisterInput("1.4.1",
                        "[]", "[\" \"]", null, null, null, "manual", null, null)));
        assertTrue(blankItem.getMessage().contains("写清理由"), "每条须写清理由非空：" + blankItem.getMessage());
    }

    @Test
    void componentMatrix_itemsMustNameComponentFamily() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> manifestService.register(new ReleaseRegisterInput("1.4.2",
                        "[{\"support\":\">=1.3\"}]", "[]", null, null, null, "manual", null, null)));
        assertTrue(e.getMessage().contains("部件"), e.getMessage());
    }

    @Test
    void latest_manifestMissing_returnsNull_honestEmptyState() {
        assertNull(manifestService.latest(), "未导入发布清单=诚实 null（空态人话由前端升级中心呈现，闸据此全放行）");
    }

    @Test
    void listAll_sortedVersionDesc() {
        manifestService.register(sample("1.3.0"));
        manifestService.register(sample("1.4.0"));
        List<ReleaseManifestInfo> all = manifestService.listAll();
        assertEquals(2, all.size());
        assertEquals("1.4.0", all.get(0).version(), "最新发版在首（横幅消费序）");
    }

    @Test
    void listAll_sortedNumericNotStringOrder() {
        manifestService.register(sample("1.9.0"));
        manifestService.register(sample("1.10.0"));
        List<ReleaseManifestInfo> all = manifestService.listAll();
        assertEquals("1.10.0", all.get(0).version(),
                "逐段数值降序（1.10>1.9），禁字符串序把旧版顶到首位");
        assertEquals("1.10.0", manifestService.latest().version(), "latest 与 listAll 首位同源");
    }
}
