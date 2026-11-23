package com.sixbean.testpike.core.token.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.token.api.dto.ApiTokenCreateInput;
import com.sixbean.testpike.core.token.api.dto.ApiTokenCreated;
import com.sixbean.testpike.core.token.api.dto.ApiTokenVerifyStatus;
import com.sixbean.testpike.core.token.api.dto.ApiTokenVerifyResult;
import com.sixbean.testpike.core.token.api.dto.ApiTokenView;
import com.sixbean.testpike.core.common.domain.component.DigestHex;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class ApiTokenServiceTest {

    @Autowired
    private ApiTokenService apiTokenService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userId;

    private long auditFloor;

    /**
 * R37 fixture-scoped teardown : tokens hang off the suite's
 * t164-* users (tp_api_token has an FK into tp_user, so the shared user-tree
 * sweep removes role links, tokens and notifications with them); the audit
 * rows the service appends carry non-user object types and are reclaimed
 * through the id-watermark window opened at setup. The old blanket TRUNCATE
 * wiped shipped tokens and every concurrent suite's audit trail.
 */
    @BeforeEach
    void setUpFixture() {
        auditFloor = SharedDbFixtureSweep.maxId(jdbcTemplate, "tp_audit_log");
        cleanFixtureRows();
        userId = jdbcTemplate.queryForObject("""
                INSERT INTO tp_user (username, password_hash, display_name, role, status)
                VALUES ('t164-owner', 'x', 'T164属主', 1, 1) RETURNING id
                """, Long.class);
    }

    @AfterEach
    void cleanFixtureRows() {
        SharedDbFixtureSweep.deleteSince(jdbcTemplate, "tp_audit_log", auditFloor);
        SharedDbFixtureSweep.usersByPatternDown(jdbcTemplate, "t164-%");
    }

    // ---- helpers ----

    private static ApiTokenCreateInput input(String name, String scopes, LocalDateTime expiresAt) {
        return new ApiTokenCreateInput(name, scopes, expiresAt);
    }

    private ApiTokenCreated createDefault() {
        return apiTokenService.create(userId, input("ci-bot", "mcp.case,mcp.read", null));
    }

    private String hashColumn(Long tokenId) {
        return jdbcTemplate.queryForObject(
                "SELECT token_hash FROM tp_api_token WHERE id = ?", String.class, tokenId);
    }


    @Test
    void 创建_明文仅响应一次_DB只落SHA256哈希() {
        ApiTokenCreated created = createDefault();

        assertNotNull(created.plaintext(), "创建响应必须含明文（仅此一次）");
        assertTrue(created.plaintext().length() >= 40, "明文需足够熵（随机 32 字节 base64url 级）");
        assertNotNull(created.id());

        String hash = hashColumn(created.id());
        assertEquals(64, hash.length(), "token_hash CHAR(64)=SHA-256 hex");
        assertEquals(DigestHex.sha256(created.plaintext()), hash,
                "DB 哈希必须=SHA-256(明文)（verify 单点可复算）");
        assertFalse(hash.contains(created.plaintext()), "明文禁为哈希子串");
        Integer leaks = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_api_token WHERE name = ? OR scopes = ?",
                Integer.class, created.plaintext(), created.plaintext());
        assertEquals(0, leaks, "明文禁落任何列（D5）");
    }


    @Test
    void scope校验族_非mcp前缀或空或超长_40001人话零写入() {
        List<ApiTokenCreateInput> bad = List.of(
                input("n", "case,read", null),
                input("n", "", null),
                input("n", "   ", null),
                input("n", "mcp.case,ftp.write", null),
                input("n", "mcp." + "a".repeat(300), null));

        for (ApiTokenCreateInput b : bad) {
            ServiceException e = assertThrows(ServiceException.class,
                    () -> apiTokenService.create(userId, b), "非法 scope 必须拒: " + b.scopes());
            assertEquals(BizErrorCode.VALIDATION_ERROR, e.getCode());
            assertTrue(e.getMessage().contains("mcp."), "人话必须点名 mcp. 前缀要求: " + e.getMessage());
        }
        // Scoped to this fixture user (shared dev DB keeps other suites' rows):
        // every rejected create below targets userId, so a run-scoped count is
        // the exact replacement for the old post-TRUNCATE global count.
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_api_token WHERE user_id = ?",
                Integer.class, userId), "校验失败零写入");
    }


    @Test
    void scope允许ci字面_近似值与混入非法项仍拒() {
        ApiTokenCreated created = apiTokenService.create(userId,
                input("ci-bot", "ci,mcp.case", null));
        ApiTokenView v = apiTokenService.listByUser(userId).stream()
                .filter(t -> t.id().equals(created.id())).findFirst().orElseThrow();
        assertEquals("ci,mcp.case", v.scopes(), "ci 与 mcp.* 可混授，归一保序");

        for (String bad : List.of("cix", "CI", "ci.", "ci.read", "mcp.case,cio")) {
            ServiceException e = assertThrows(ServiceException.class,
                    () -> apiTokenService.create(userId, input("n", bad, null)),
                    "ci 近似值必须拒: " + bad);
            assertEquals(BizErrorCode.VALIDATION_ERROR, e.getCode());
        }
    }

 // ---- ②c external-agent tooling scope vocabulary: four new mcp.* values accepted as-is ----

    @Test
    void scope允许四枚工具域值_裸前缀与非前缀仍拒() {
        for (String good : List.of("mcp.doc", "mcp.gen", "mcp.script", "mcp.exec",
                "ci,mcp.doc,mcp.exec")) {
            ApiTokenCreated created = apiTokenService.create(userId, input("n", good, null));
            ApiTokenView v = apiTokenService.listByUser(userId).stream()
                    .filter(t -> t.id().equals(created.id())).findFirst().orElseThrow();
            assertEquals(good, v.scopes(), "新值域归一保序存储: " + good);
        }
        for (String bad : List.of("mcp.", "doc")) {
            ServiceException e = assertThrows(ServiceException.class,
                    () -> apiTokenService.create(userId, input("n", bad, null)),
                    "裸前缀/非前缀必须拒: " + bad);
            assertEquals(BizErrorCode.VALIDATION_ERROR, e.getCode());
        }
    }


    @Test
    void 列表_前8位指纹_零哈希零明文外泄() {
        ApiTokenCreated created = apiTokenService.create(userId,
                input("ci-bot", "mcp.case", LocalDateTime.of(2099, 1, 1, 0, 0)));

        List<ApiTokenView> views = apiTokenService.listByUser(userId);
        assertEquals(1, views.size());
        ApiTokenView v = views.get(0);
        assertEquals("ci-bot", v.name());
        assertEquals("mcp.case", v.scopes());
        String hash = hashColumn(created.id());
        assertEquals(hash.substring(0, 8), v.fingerprint(), "指纹=哈希前 8 位（02 口径）");
        assertFalse(created.plaintext().startsWith(v.fingerprint())
                || hash.length() < 8, "指纹禁等于明文前缀可反推面（哈希前缀不含明文信息）");
        assertEquals(0, v.revoked());
        assertNull(v.lastUsedAt(), "从未使用 last_used_at=NULL");
        assertNotNull(v.expiresAt());
    }


    @Test
    void 吊销_后verify返REVOKED人话_重复吊销幂等() {
        ApiTokenCreated created = createDefault();

        apiTokenService.revoke(created.id(), userId);
        ApiTokenVerifyResult after = apiTokenService.verify(created.plaintext());
        assertFalse(after.ok());
        assertEquals(ApiTokenVerifyStatus.REVOKED, after.status());
        assertTrue(after.message().contains("吊销"), "IT-MCP-01 逐类人话: " + after.message());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT revoked FROM tp_api_token WHERE id = ?", Integer.class, created.id()));

        apiTokenService.revoke(created.id(), userId);
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_api_token WHERE id = ?", Integer.class, created.id()));
    }


    @Test
    void 非属主非管理员吊销_40301人话_吊销态不变_登录态空40101() {
        ApiTokenCreated created = createDefault();
        Long strangerId = insertUser("t164-stranger", 1);

        ServiceException e = assertThrows(ServiceException.class,
                () -> apiTokenService.revoke(created.id(), strangerId));
        assertEquals(BizErrorCode.FORBIDDEN, e.getCode());
        assertTrue(e.getMessage().contains("本人"), "人话指路: " + e.getMessage());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT revoked FROM tp_api_token WHERE id = ?", Integer.class, created.id()),
                "越权吊销零副作用");

        ServiceException anon = assertThrows(ServiceException.class,
                () -> apiTokenService.revoke(created.id(), null));
        assertEquals(BizErrorCode.UNAUTHORIZED, anon.getCode());
    }

    @Test
    void 系统管理员代吊销_放行_属主本人吊销不受影响() {
        ApiTokenCreated byAdmin = createDefault();
        Long adminId = insertUser("t164-admin", 4);
        apiTokenService.revoke(byAdmin.id(), adminId);
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT revoked FROM tp_api_token WHERE id = ?", Integer.class, byAdmin.id()));

        ApiTokenCreated mine = apiTokenService.create(userId, input("ci-2", "mcp.read", null));
        apiTokenService.revoke(mine.id(), userId);
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT revoked FROM tp_api_token WHERE id = ?", Integer.class, mine.id()));
    }

    private Long insertUser(String username, int role) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO tp_user (username, password_hash, display_name, role, status)
                VALUES (?, 'x', ?, ?, 1) RETURNING id
                """, Long.class, username, username, role);
    }


    @Test
    void verify正常_OK返回属主与scope_last_used_at刷新() {
        ApiTokenCreated created = apiTokenService.create(userId,
                input("ci-bot", "mcp.case,mcp.read", null));
        jdbcTemplate.update("UPDATE tp_api_token SET last_used_at = NULL WHERE id = ?", created.id());

        ApiTokenVerifyResult ok = apiTokenService.verify(created.plaintext());
        assertTrue(ok.ok());
        assertEquals(ApiTokenVerifyStatus.OK, ok.status());
        assertEquals(userId, ok.userId());
        assertEquals(List.of("mcp.case", "mcp.read"), ok.scopes());
        assertNotNull(jdbcTemplate.queryForObject(
                "SELECT last_used_at FROM tp_api_token WHERE id = ?",
                java.sql.Timestamp.class, created.id()), "认证通过必刷新 last_used_at（V43 裁定⑥）");
    }


    @Test
    void verify未知Token_INVALID人话() {
        ApiTokenVerifyResult r = apiTokenService.verify("tp_pat_not-in-db-xxxx");
        assertFalse(r.ok());
        assertEquals(ApiTokenVerifyStatus.INVALID, r.status());
        assertNotNull(r.message(), "无/错 Token 必须人话（IT-MCP-01）");
    }


    @Test
    void verify过期_EXPIRED人话含时间_永久Token不受影响() {
        ApiTokenCreated expired = apiTokenService.create(userId,
                input("old", "mcp.case", LocalDateTime.now().minusDays(1)));
        ApiTokenVerifyResult r = apiTokenService.verify(expired.plaintext());
        assertFalse(r.ok());
        assertEquals(ApiTokenVerifyStatus.EXPIRED, r.status());
        assertTrue(r.message().contains("过期"), "逐类人话: " + r.message());

        ApiTokenCreated forever = createDefault();
        assertTrue(apiTokenService.verify(forever.plaintext()).ok());
    }


    @Test
    void 属主停用或软删_Token认证被拒人话() {
        ApiTokenCreated created = createDefault();
        jdbcTemplate.update("UPDATE tp_user SET status = 0 WHERE id = ?", userId);
        ApiTokenVerifyResult disabled = apiTokenService.verify(created.plaintext());
        assertFalse(disabled.ok(), "停用用户 Token 禁认证（认证原点守）");

        jdbcTemplate.update("UPDATE tp_user SET status = 1, deleted = 1 WHERE id = ?", userId);
        assertFalse(apiTokenService.verify(created.plaintext()).ok(), "软删用户 Token 禁认证");
    }

    // ---- ⑨ GA1 token project scope (piece A: data + auth origin) ----

    /** Four-field create shape introduced by GA1 (name, scopes, expiresAt, projectScope). */
    private static ApiTokenCreateInput input(String name, String scopes,
                                             LocalDateTime expiresAt, String projectScope) {
        return new ApiTokenCreateInput(name, scopes, expiresAt, projectScope);
    }

    @Test
    void legacyTokensWithoutProjectScopeVerifyUnrestricted() {
        ApiTokenCreated created = createDefault();

        ApiTokenVerifyResult ok = apiTokenService.verify(created.plaintext());
        assertTrue(ok.ok());
        assertEquals(List.of(), ok.projectScope(),
                "NULL pin = no narrowing, the gateway keeps the owner-membership check only");
        ApiTokenView v = apiTokenService.listByUser(userId).stream()
                .filter(t -> t.id().equals(created.id())).findFirst().orElseThrow();
        assertNull(v.projectScope(), "legacy rows expose null (zero drift for shipped tokens)");
    }

    @Test
    void projectScopeNormalizedToSortedUniqueIdsAndEchoedBack() {
        ApiTokenCreated created = apiTokenService.create(userId,
                input("pin", "mcp.case", null, " 12 , 3 , 12 ,3 "));

        ApiTokenView v = apiTokenService.listByUser(userId).stream()
                .filter(t -> t.id().equals(created.id())).findFirst().orElseThrow();
        assertEquals("3,12", v.projectScope(), "numeric ids de-duplicated, ascending, no spaces");

        ApiTokenVerifyResult ok = apiTokenService.verify(created.plaintext());
        assertTrue(ok.ok());
        assertEquals(List.of("3", "12"), ok.projectScope(),
                "verify returns the parsed pin so the api gateway enforces it without a re-read");
    }

    @Test
    void nonNumericIdOrOverlongProjectScopeRejectedBeforeDb() {
        for (String bad : List.of("abc", "12x", "-3", "1.5", "4;5", "main-project")) {
            ServiceException e = assertThrows(ServiceException.class,
                    () -> apiTokenService.create(userId, input("n", "mcp.case", null, bad)),
                    "non-numeric project id must be rejected: " + bad);
            assertEquals(BizErrorCode.VALIDATION_ERROR, e.getCode());
        }
        String tooLong = java.util.stream.IntStream.rangeClosed(1, 101)
                .mapToObj(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
        assertTrue(tooLong.length() > 200);
        ServiceException e = assertThrows(ServiceException.class,
                () -> apiTokenService.create(userId, input("n", "mcp.case", null, tooLong)),
                "over-column-width project pin must be rejected before touching the DB");
        assertEquals(BizErrorCode.VALIDATION_ERROR, e.getCode());
        assertTrue(e.getMessage().contains("200"), "rejection names the limit: " + e.getMessage());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_api_token WHERE user_id = ?",
                Integer.class, userId), "rejected creates write nothing");
    }
}
