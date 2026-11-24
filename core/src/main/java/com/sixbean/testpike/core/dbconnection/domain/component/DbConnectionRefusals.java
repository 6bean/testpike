package com.sixbean.testpike.core.dbconnection.domain.component;

/**
 * Single-source refusal wording for the database-connection create/update/probe
 * doors under the R63 smart-guidance contract: every sentence echoes the
 * offending submitted value (never a secret), quotes the on-screen repair
 * entry in 「」 and keeps the legacy capability clause contiguous so existing
 * assertions hold.
 *
 * <p>Quoted literals must match the shipped frontend wording (the project
 * data-source page registers connections; environment choices come from the
 * environment config page; the production-host blacklist is a deployment key).
 * Keep them in sync when the screens change.
 */
public final class DbConnectionRefusals {

    /** Repair entry quoted for connection registration and duplicate names. */
    public static final String DATASOURCE_ENTRY = "「数据源管理」页";

    /** Repair entry quoted for environment-related refusals. */
    public static final String ENV_ENTRY = "「环境配置」页";

    private DbConnectionRefusals() {
    }

    public static String maskedPasswordSubmitted(String placeholder) {
        return "密码不能提交打码占位值，请输入真实密码后保存（本次提交= " + placeholder
                + " 为列表回显的打码样式）；请在" + DATASOURCE_ENTRY
                + "的连接登记表单重新输入真实密码";
    }

    public static String connectionNotFound(long id, long projectId) {
        return "连接不存在或不属于该项目（连接 id= " + id + "，项目 id= " + projectId
                + "）；请到" + DATASOURCE_ENTRY + "核对连接编号与顶栏所属项目后重试";
    }

    public static String envMissing(long envId, long projectId) {
        return "所选环境不存在或不属于该项目（环境 id= " + envId + "，项目 id= " + projectId
                + "）；请到" + ENV_ENTRY + "确认环境仍在当前项目下后重选";
    }

    public static String prodEnvForbidden(long envId) {
        return "禁止为生产环境配置数据库连接（生产防护），请在测试/预发环境配置（本次环境 id= " + envId
                + " 为生产类型）；可在" + ENV_ENTRY + "改选测试/预发环境或新建非生产环境";
    }

    public static String prodHostBlacklisted(String jdbcUrl) {
        // Echo only the host token: jdbc urls may embed user/password query params,
        // so the raw url must never reach a screen message (credential red line).
        return "JDBC 主机命中生产域名黑名单，拒绝保存；黑名单配置项=TESTPIKE_PROD_BLACKLIST"
                + "（本次主机= " + hostOf(jdbcUrl) + "）；如属误判，请在部署环境的 TESTPIKE_PROD_BLACKLIST "
                + "中核对该域名后重新保存，或在" + DATASOURCE_ENTRY + "改填测试库地址";
    }

    private static String hostOf(String jdbcUrl) {
        String text = jdbcUrl == null ? "" : jdbcUrl.trim();
        int schemeEnd = text.indexOf("//");
        String rest = schemeEnd >= 0 ? text.substring(schemeEnd + 2) : text;
        int cut = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '/' || c == '?' || c == ';') {
                cut = i;
                break;
            }
        }
        return rest.substring(0, cut);
    }

    public static String nameInvalid(int length) {
        return "连接名不能为空且不超过 100 字（本次提交长度= " + length + "）；请在"
                + DATASOURCE_ENTRY + "的连接登记表单修正名称后保存";
    }

    public static String usernameMissing() {
        return "数据库账号不能为空；请在" + DATASOURCE_ENTRY + "的连接登记表单填写数据库账号（username）后保存";
    }

    public static String passwordMissing() {
        return "数据库密码不能为空；请在" + DATASOURCE_ENTRY
                + "的连接登记表单填写数据库密码（保存后列表只回显打码样式，不回显明文）";
    }

    public static String cipherDisabled() {
        return "系统未配置 CONFIG_AES_KEY，无法加密保存连接密码，请先在部署环境配置该密钥后重试"
                + "（配置位置=服务端部署目录 .env 的 CONFIG_AES_KEY，改后需重启服务生效）";
    }

    public static String duplicateName(String name) {
        return "该项目已存在同名连接「" + name + "」，请更换连接名；可在" + DATASOURCE_ENTRY
                + "列表确认现有连接后为本次登记改名（如加环境后缀）";
    }

    public static String poolOutOfRange(int submitted) {
        return "连接池上限仅支持 1-5（本次提交= " + submitted + "）；请在"
                + DATASOURCE_ENTRY + "的连接登记表单把连接池上限调整为 1~5 后保存";
    }
}
