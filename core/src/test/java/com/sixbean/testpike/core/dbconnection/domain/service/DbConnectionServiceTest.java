package com.sixbean.testpike.core.dbconnection.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.domain.component.ConfigCipher;
import com.sixbean.testpike.core.dbconnection.api.dto.ConnectivityTestInfo;
import com.sixbean.testpike.core.dbconnection.api.dto.DbConnectionInfo;
import com.sixbean.testpike.core.dbconnection.api.dto.DbConnectionInput;
import com.sixbean.testpike.core.project.api.dto.ProjectEnvInput;
import com.sixbean.testpike.core.project.domain.service.ProjectEnvService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.ProjectFixtureRig;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class DbConnectionServiceTest {

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ProjectEnvService envService;

    @Autowired
    private DbConnectionService connectionService;

    @Autowired
    private ConfigCipher configCipher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long devEnvId;

    /**
 * R37 fixture-scoped teardown : the old blanket TRUNCATE
 * wiped shipped projects, users, envs and audit rows on the shared dev
 * database. The sweep deletes only rows reachable from this suite's
 * tracked fixture projects; tp_db_connection carries a project_id but no
 * FK into tp_project, hence the extra project-keyed table. Crashed-run
 * residue self-heals through the fixed project codes in createSelfHeal.
 */
    private final List<Long> trackedProjects = new ArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanFixtureRows() {
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, List.of("tp_db_connection"), trackedProjects);
        trackedProjects.clear();
    }

    private void initProject() {
        projectId = ProjectFixtureRig.createSelfHeal(jdbcTemplate, projectService, 5L,
                trackedProjects, "数据层中台", "PDB");
        devEnvId = envService.create(projectId, new ProjectEnvInput("test", 2,
                "http://test.internal", "{}", "web", "chromium", null));
    }

    
    private DbConnectionInput pgInput(String name) {
        return new DbConnectionInput(devEnvId, name, "pg", System.getenv("DB_URL"),
                System.getenv("DB_USERNAME"), System.getenv("DB_PASSWORD"), true, 2);
    }

    @Test
    void create_生产环境禁建连接_403先于连库() {
        initProject();
        long prodEnvId = envService.create(projectId, new ProjectEnvInput("prod", 4,
                "https://prod.internal", "{}", "web", "chromium", null));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> connectionService.create(projectId, new DbConnectionInput(prodEnvId, "p", "pg",
                        System.getenv("DB_URL"), "u", "pwd", true, 2)));
        assertEquals(BizErrorCode.FORBIDDEN, ex.getCode(), "生产环境连接拒绝（IT-B11-01，先于连库 fail-fast）");
        assertTrue(ex.getMessage().contains("生产"), "人话提示含「生产」");
    }

    @Test
    void create_主机命中黑名单_403人话指路() {
        initProject();
        ServiceException ex = assertThrows(ServiceException.class,
                () -> connectionService.create(projectId, new DbConnectionInput(devEnvId, "bl", "pg",
                        "jdbc:postgresql://db.example.com:5432/x", "u", "pwd", true, 2)));
        assertEquals(BizErrorCode.FORBIDDEN, ex.getCode());
        assertTrue(ex.getMessage().contains("TESTPIKE_PROD_BLACKLIST"), "指路配置项名（人话可自救）");
    }

    @Test
    void create_同名冲突_409() {
        initProject();
        connectionService.create(projectId, pgInput("主库"));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> connectionService.create(projectId, pgInput("主库")));
        assertEquals(BizErrorCode.RESOURCE_CONFLICT, ex.getCode());
        assertTrue(ex.getMessage().contains("主库"), "冲突提示含连接名");
    }

    @Test
    void create_未注册方言_400人话() {
        initProject();
        ServiceException ex = assertThrows(ServiceException.class,
                () -> connectionService.create(projectId, new DbConnectionInput(devEnvId, "o", "oracle",
                        "jdbc:oracle:thin:@h:1521:x", "u", "pwd", true, 2)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("oracle"), "点名不支持的方言");
    }

    @Test
    void create_jdbcUrl前缀与方言不匹配_400() {
        initProject();
        ServiceException ex = assertThrows(ServiceException.class,
                () -> connectionService.create(projectId, new DbConnectionInput(devEnvId, "m", "mysql",
                        "jdbc:postgresql://localhost:15432/testpike", "u", "pwd", true, 2)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
    }

    @Test
    void list_出参恒打码_库内为密文() {
        initProject();
        connectionService.create(projectId, pgInput("主库"));
        List<DbConnectionInfo> list = connectionService.list(projectId);
        assertEquals(1, list.size());
        assertEquals(ConfigCipher.MASK, list.get(0).password(), "出参恒 ******（TC-B11-06）");
        String enc = jdbcTemplate.queryForObject(
                "SELECT password_enc FROM tp_db_connection WHERE project_id = ?", String.class, projectId);
        assertNotNull(enc);
        assertFalse(enc.equals(System.getenv("DB_PASSWORD")), "库内禁明文");
        assertEquals(System.getenv("DB_PASSWORD"), configCipher.decrypt(enc), "密文可解=真值仅内部流转");
    }

    @Test
    void update_MASK回传保留原密文() {
        initProject();
        long id = connectionService.create(projectId, pgInput("主库"));
        String encBefore = jdbcTemplate.queryForObject(
                "SELECT password_enc FROM tp_db_connection WHERE id = ?", String.class, id);

        DbConnectionInfo updated = connectionService.update(projectId, id,
                new DbConnectionInput(devEnvId, "主库", "pg", System.getenv("DB_URL"),
                        "newuser", ConfigCipher.MASK, true, 3));

        assertEquals("newuser", updated.username());
        assertEquals(3, updated.maxPool());
        String encAfter = jdbcTemplate.queryForObject(
                "SELECT password_enc FROM tp_db_connection WHERE id = ?", String.class, id);
        assertEquals(encBefore, encAfter, "MASK 提交=保留原密文（K8 编辑语义）");
    }

    @Test
    void test_pg连通成功真SELECT1_mysql缺证人话拒() {
        initProject();
        long pgId = connectionService.create(projectId, pgInput("平台库"));
        ConnectivityTestInfo ok = connectionService.test(projectId, pgId);
        assertTrue(ok.passed(), "真连平台库 SELECT 1（L2 亦真实链路，禁 mock）");

        long mysqlId = connectionService.create(projectId, new DbConnectionInput(devEnvId, "my", "mysql",
                "jdbc:mysql://localhost:3306/test", "u", "pwd", true, 2));
        ConnectivityTestInfo miss = connectionService.test(projectId, mysqlId);
        assertFalse(miss.passed());
        assertTrue(miss.message().contains("未装 mysql 驱动"), "R4 缺证人话（core pom 仅 postgresql=有意）");
    }

    @Test
    void create_跨项目环境_403() {
        initProject();
        long p2 = ProjectFixtureRig.createSelfHeal(jdbcTemplate, projectService, 5L,
                trackedProjects, "项目乙", "PB2");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> connectionService.create(p2, new DbConnectionInput(devEnvId, "x", "pg",
                        System.getenv("DB_URL"), "u", "pwd", true, 2)));
        assertEquals(BizErrorCode.FORBIDDEN, ex.getCode(), "env 不属于该项目=403（H1 隔离）");
    }

    @Test
    void delete_删除成功后列表为空() {
        initProject();
        long id = connectionService.create(projectId, pgInput("主库"));
        connectionService.delete(projectId, id);
        assertEquals(0, connectionService.list(projectId).size());
    }
}
