package com.sixbean.testpike.core.dbconnection.domain.component;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R63 contract for the db-connection registration/probe doors: every refusal
 * echoes the offending submitted value (never a secret), names the on-screen
 * repair entry, and keeps the legacy capability clause contiguous so existing
 * assertions stay green.
 */
class DbConnectionRefusalsL2Test {

    private static void assertKeepsLegacy(String message, String legacy) {
        assertTrue(message.contains(legacy),
                "legacy clause must stay contiguous. actual: " + message);
    }

    private static void assertHasRepairEntry(String message) {
        assertTrue(message.contains("「") || message.contains("CONFIG_AES_KEY")
                        || message.contains("TESTPIKE_PROD_BLACKLIST"),
                "message must name an on-screen entry or a config key. actual: " + message);
    }

    @Test
    void maskedPasswordEchoesPlaceholderAndNamesEntry() {
        String msg = DbConnectionRefusals.maskedPasswordSubmitted("******");
        assertKeepsLegacy(msg, "密码不能提交打码占位值，请输入真实密码后保存");
        assertTrue(msg.contains("******"), "must echo the submitted placeholder. actual: " + msg);
        assertHasRepairEntry(msg);
    }

    @Test
    void connectionNotFoundEchoesIdAndProject() {
        String msg = DbConnectionRefusals.connectionNotFound(77L, 9L);
        assertKeepsLegacy(msg, "连接不存在或不属于该项目");
        assertTrue(msg.contains("77") && msg.contains("9"), "must echo ids. actual: " + msg);
        assertHasRepairEntry(msg);
    }

    @Test
    void envMissingEchoesEnvAndProject() {
        String msg = DbConnectionRefusals.envMissing(31L, 9L);
        assertKeepsLegacy(msg, "所选环境不存在或不属于该项目");
        assertTrue(msg.contains("31") && msg.contains("9"), "must echo ids. actual: " + msg);
        assertHasRepairEntry(msg);
    }

    @Test
    void prodEnvForbiddenEchoesEnvIdAndPointsToEnvPage() {
        String msg = DbConnectionRefusals.prodEnvForbidden(44L);
        assertKeepsLegacy(msg, "禁止为生产环境配置数据库连接（生产防护），请在测试/预发环境配置");
        assertTrue(msg.contains("44"), "must echo the env id. actual: " + msg);
        assertHasRepairEntry(msg);
    }

    @Test
    void prodHostBlacklistedEchoesHostAndKeepsKey() {
        String msg = DbConnectionRefusals.prodHostBlacklisted("jdbc:postgresql://prod-db.corp:5432/x");
        assertKeepsLegacy(msg, "JDBC 主机命中生产域名黑名单，拒绝保存；黑名单配置项=TESTPIKE_PROD_BLACKLIST");
        assertTrue(msg.contains("prod-db.corp"), "must echo the offending host. actual: " + msg);
        assertHasRepairEntry(msg);
    }

    @Test
    void nameInvalidEchoesActualLength() {
        String msg = DbConnectionRefusals.nameInvalid(214);
        assertKeepsLegacy(msg, "连接名不能为空且不超过 100 字");
        assertTrue(msg.contains("214"), "must echo the actual length. actual: " + msg);
        assertHasRepairEntry(msg);
    }

    @Test
    void usernameMissingNamesFieldAndEntry() {
        String msg = DbConnectionRefusals.usernameMissing();
        assertKeepsLegacy(msg, "数据库账号不能为空");
        assertTrue(msg.contains("账号"), "clause keeps the field word");
        assertHasRepairEntry(msg);
    }

    @Test
    void passwordMissingNamesEntryWithoutEchoingSecret() {
        String msg = DbConnectionRefusals.passwordMissing();
        assertKeepsLegacy(msg, "数据库密码不能为空");
        assertFalse(msg.contains("password="), "must not fabricate a value echo for secrets. actual: " + msg);
        assertHasRepairEntry(msg);
    }

    @Test
    void cipherDisabledKeepsKeyAndGivesDeployPath() {
        String msg = DbConnectionRefusals.cipherDisabled();
        assertKeepsLegacy(msg, "系统未配置 CONFIG_AES_KEY，无法加密保存连接密码，请先在部署环境配置该密钥后重试");
        assertHasRepairEntry(msg);
    }

    @Test
    void duplicateNameKeepsQuotedNameAndNamesEntry() {
        String msg = DbConnectionRefusals.duplicateName("订单库只读");
        assertKeepsLegacy(msg, "该项目已存在同名连接「订单库只读」，请更换连接名");
        assertHasRepairEntry(msg);
    }

    @Test
    void poolOutOfRangeEchoesSubmittedValue() {
        String msg = DbConnectionRefusals.poolOutOfRange(12);
        assertKeepsLegacy(msg, "连接池上限仅支持 1-5");
        assertTrue(msg.contains("12"), "must echo the submitted pool. actual: " + msg);
        assertHasRepairEntry(msg);
    }
}
