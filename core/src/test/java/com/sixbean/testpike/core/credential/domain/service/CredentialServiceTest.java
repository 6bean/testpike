package com.sixbean.testpike.core.credential.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.rbac.PermissionCatalog;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.credential.api.dto.CredentialInfo;
import com.sixbean.testpike.core.credential.api.dto.CredentialInput;
import com.sixbean.testpike.core.config.domain.component.ConfigCipher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class CredentialServiceTest {

    @Autowired
    private CredentialService credentialService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long otherProjectId;

    @BeforeEach
    void setUp() {
        String code = "crd" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "凭据测试项目-" + code, code);
        projectId = jdbcTemplate.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, code);
        String otherCode = "cre" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "凭据他项目-" + otherCode, otherCode);
        otherProjectId = jdbcTemplate.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, otherCode);
        cleanCredentials();
    }

    @AfterEach
    void tearDown() {
        cleanCredentials();
        jdbcTemplate.update("DELETE FROM tp_project WHERE id IN (?, ?)", projectId, otherProjectId);
    }

    private void cleanCredentials() {
        if (projectId != 0) {
            jdbcTemplate.update("DELETE FROM tp_credential WHERE project_id IN (?, ?)",
                    projectId, otherProjectId);
        }
    }

    private CredentialInput input(String key, String secret) {
        return new CredentialInput("测试账号-" + key, key, secret, "样例 App 登录口令");
    }

    private String rawCipher(long id) {
        return jdbcTemplate.queryForObject(
                "SELECT secret_cipher FROM tp_credential WHERE id = ?", String.class, id);
    }


    @Test
    void create_encryptsAtRestAndNeverEchoesPlaintext() {
        long id = credentialService.create(projectId, input("qa_login", "P@ssw0rd-秘密"));
        assertTrue(id > 0);

        String cipher = rawCipher(id);
        assertTrue(cipher.startsWith("ENC:"),
                "secret_cipher 必须落 ENC: 前缀密文形态，实际=" + cipher.substring(0, Math.min(20, cipher.length())));
        assertFalse(cipher.contains("P@ssw0rd"), "库内原文禁含明文口令");

        CredentialInfo info = credentialService.get(id);
        assertEquals("qa_login", info.credKey());
        assertEquals(ConfigCipher.MASK, info.secretMask(), "读取口必须打码，永不回显明文");
        assertNotNull(info.createdAt());
        assertEquals("样例 App 登录口令", info.remark());
        assertEquals(1, info.status(), "新建默认启用");
    }


    @Test
    void create_rejectsInvalidKeysNamingAllViolations() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> credentialService.create(projectId, input("bad key!x", "s3cret")));
        assertTrue(ex.getMessage().contains("bad key!x"),
                "违例键必须一次点名，实际=" + ex.getMessage());

        assertThrows(ServiceException.class,
                () -> credentialService.create(projectId, input("1starts_digit", "s3cret")));
        assertThrows(ServiceException.class,
                () -> credentialService.create(projectId, input("k".repeat(65), "s3cret")));

        long id = credentialService.create(projectId, input("_ok_key9", "s3cret"));
        assertTrue(id > 0);
    }


    @Test
    void create_duplicateKeyWithinProjectConflicts_acrossProjectsAllowed() {
        credentialService.create(projectId, input("qa_login", "first"));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> credentialService.create(projectId, input("qa_login", "second")));
        assertTrue(ex.getMessage().contains("qa_login"), "撞键人话必须点名键，实际=" + ex.getMessage());

        long other = credentialService.create(otherProjectId, input("qa_login", "cross-project"));
        assertTrue(other > 0, "跨项目同键合法（凭据是项目资产）");
    }


    @Test
    void create_rejectsBlankSecret() {
        assertThrows(ServiceException.class,
                () -> credentialService.create(projectId, input("qa_login", "  ")));
        assertThrows(ServiceException.class,
                () -> credentialService.create(projectId, new CredentialInput("n", "qa_login", null, null)));
    }


    @Test
    void update_maskValueKeepsCipher_newSecretReplaces() {
        long id = credentialService.create(projectId, input("qa_login", "original-secret"));
        String cipherBefore = rawCipher(id);

        credentialService.update(id, new CredentialInput("改名后", "qa_login", ConfigCipher.MASK, "备注更新"));
        assertEquals(cipherBefore, rawCipher(id), "提交打码占位值=未修改语义，原密文必须保留");
        CredentialInfo after = credentialService.get(id);
        assertEquals("改名后", after.name());
        assertEquals("备注更新", after.remark());

        credentialService.update(id, input("qa_login", "rotated-secret"));
        String cipherRotated = rawCipher(id);
        assertFalse(cipherRotated.equals(cipherBefore), "新口令必须重新加密覆盖");
        assertEquals("rotated-secret", credentialService.revealSecret(id), "解密内部口=轮转后明文");
    }


    @Test
    void setEnabled_persistsStatusFlag() {
        long id = credentialService.create(projectId, input("qa_login", "s"));
        credentialService.setEnabled(id, false);
        assertEquals(0, credentialService.get(id).status());

        credentialService.setEnabled(id, true);
        assertEquals(1, credentialService.get(id).status());

        List<CredentialInfo> enabledOnly = credentialService.list(projectId, true);
        assertEquals(1, enabledOnly.size());
    }


    @Test
    void revealSecret_roundTripsPlaintext_missingGivesFriendlyNotFound() {
        long id = credentialService.create(projectId, input("mp_ticket", "ticket-value-123"));
        assertEquals("ticket-value-123", credentialService.revealSecret(id));

        assertThrows(ServiceException.class, () -> credentialService.get(999_999_999L));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> credentialService.revealSecret(999_999_999L));
        assertTrue(ex.getMessage().contains("凭据"), "人话文案，实际=" + ex.getMessage());
    }


    @Test
    void resolveSecret_byProjectAndKey_failClosedOnDisabledAndUnknown() {
        String plain = "alpha-" + "one";
        long id = credentialService.create(projectId, input("qa_login", plain));
        assertEquals(plain, credentialService.resolveSecret(projectId, "qa_login"),
                "取密通道=按 projectId+引用键解密返回明文（派发前注入单点消费）");

        ServiceException missing = assertThrows(ServiceException.class,
                () -> credentialService.resolveSecret(otherProjectId, "qa_login"));
        assertTrue(missing.getMessage().contains("qa_login"),
                "未建档必须人话点名键，实际=" + missing.getMessage());
        assertFalse(missing.getMessage().contains(plain), "错误文案零实值（红线 4）");

        credentialService.setEnabled(id, false);
        ServiceException disabled = assertThrows(ServiceException.class,
                () -> credentialService.resolveSecret(projectId, "qa_login"));
        assertTrue(disabled.getMessage().contains("已禁用"),
                "禁用位必须 fail-closed 人话拒，实际=" + disabled.getMessage());
        assertTrue(disabled.getMessage().contains("qa_login"), "禁用拒必须点名键");
    }


    @Test
    void secretUsagePaths_alwaysLeaveAuditTrail_withZeroPlaintext() {
        String plain = "s3cr3t-" + "审计探针";
        long id = credentialService.create(projectId, input("qa_audit", plain));

        long baseResolve = countCredentialAudit("resolve");
        long baseReveal = countCredentialAudit("reveal");

        assertEquals(plain, credentialService.resolveSecret(projectId, "qa_audit"));
        assertEquals(1, countCredentialAudit("resolve") - baseResolve,
                "resolveSecret 解密位必须留取用审计痕（每次解密取用留痕）");

        assertEquals(plain, credentialService.revealSecret(id));
        assertEquals(1, countCredentialAudit("reveal") - baseReveal,
                "revealSecret 解密位必须留取用审计痕");

        assertThrows(ServiceException.class,
                () -> credentialService.resolveSecret(otherProjectId, "ghost_key"));
        assertEquals(1, countCredentialAudit("resolve") - baseResolve - 1,
                "取密被拒同样留痕（失败旁路审计），失败快照只含人话零实值");

        List<String> payloads = jdbcTemplate.queryForList(
                "SELECT coalesce(before_json::text, '') || '|' || coalesce(after_json::text, '') "
                        + "FROM tp_audit_log "
                        + "WHERE object_type = 'credential' AND action IN ('resolve', 'reveal')",
                String.class);
        assertFalse(payloads.isEmpty(), "应已存在取用痕行（本断言防查询写错静默空过）");
        for (String payload : payloads) {
            assertFalse(payload.contains(plain),
                    "审计留痕禁含明文凭据（红线 4），实际快照=" + payload);
        }
    }

    private long countCredentialAudit(String action) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_audit_log "
                        + "WHERE object_type = 'credential' AND action = ?",
                Long.class, action);
        return n == null ? 0L : n;
    }


    @Test
    void credentialPermissionKeys_registeredInCatalog() {
        assertTrue(PermissionCatalog.allKeys().contains("perm:appauto:cred:view"),
                "凭据查看键必须入册（§14.6 G5 清单 28 枚 appauto 侧）");
        assertTrue(PermissionCatalog.allKeys().contains("perm:appauto:cred:manage"),
                "凭据管理键必须入册");
    }
}
