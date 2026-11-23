package com.sixbean.testpike.core.config.domain.component;

import com.sixbean.testpike.core.executor.domain.component.ProdBlacklistGuard;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * / {@code exec.net-assert.enabled} / {@code sql.assert.timeout-seconds}
 *
 */
@Component
public class ExecPolicyReader {

    public static final String KEY_ALLOW_HOSTS = "exec.http.allow-hosts";
    public static final String KEY_TIMEOUT_SECONDS = "exec.http.timeout-seconds";
    public static final String KEY_NET_ASSERT_ENABLED = "exec.net-assert.enabled";
 
    public static final String KEY_SQL_ASSERT_TIMEOUT = "sql.assert.timeout-seconds";
    public static final String KEY_SQL_ASSERT_INTERVAL = "sql.assert.interval-ms";
 
    public static final String KEY_SQL_MAX_ROWS = "sql.max-rows";
 
    public static final String KEY_CONSOLE_ASSERT_MODE = "exec.console-assert.mode";

 
    public static final int DEFAULT_TIMEOUT_SECONDS = 10;
    public static final int DEFAULT_NET_ASSERT_ENABLED = 1;
    public static final int DEFAULT_SQL_ASSERT_TIMEOUT_SECONDS = 30;
    public static final int DEFAULT_SQL_ASSERT_INTERVAL_MS = 2000;
    public static final int DEFAULT_SQL_MAX_ROWS = 1000;
 
    public static final String DEFAULT_CONSOLE_ASSERT_MODE = "fail";

    private final SystemConfigReader configReader;
    private final ProdBlacklistGuard prodBlacklistGuard;

    public ExecPolicyReader(SystemConfigReader configReader,
                            ProdBlacklistGuard prodBlacklistGuard) {
        this.configReader = configReader;
        this.prodBlacklistGuard = prodBlacklistGuard;
    }

    
    public Map<String, Object> toClaimPayload() {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("allowHosts", configReader.getString(KEY_ALLOW_HOSTS, ""));
        policy.put("denyHosts", prodBlacklistGuard.patterns().stream()
                .collect(Collectors.joining(",")));
        policy.put("timeoutSeconds", configReader.getInt(KEY_TIMEOUT_SECONDS, DEFAULT_TIMEOUT_SECONDS));
        policy.put("netAssertEnabled", configReader.getInt(KEY_NET_ASSERT_ENABLED, DEFAULT_NET_ASSERT_ENABLED));
        policy.put("sqlAssertTimeoutSeconds",
                configReader.getInt(KEY_SQL_ASSERT_TIMEOUT, DEFAULT_SQL_ASSERT_TIMEOUT_SECONDS));
        policy.put("sqlAssertIntervalMs",
                configReader.getInt(KEY_SQL_ASSERT_INTERVAL, DEFAULT_SQL_ASSERT_INTERVAL_MS));
        policy.put("sqlMaxRows", configReader.getInt(KEY_SQL_MAX_ROWS, DEFAULT_SQL_MAX_ROWS));
        policy.put("consoleAssertMode",
                configReader.getString(KEY_CONSOLE_ASSERT_MODE, DEFAULT_CONSOLE_ASSERT_MODE));
        return policy;
    }
}
