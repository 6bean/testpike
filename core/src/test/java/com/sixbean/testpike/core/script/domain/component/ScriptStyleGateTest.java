package com.sixbean.testpike.core.script.domain.component;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.config.domain.component.SystemConfigReader;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class ScriptStyleGateTest {

    private static final String KEY = "script.style-gate.enabled";

    
    private static final String VIOLATING = """
            {"version":"v2","setup":[],
             "steps":[
               {"action":"wait","ms":3000},
               {"action":"click","target":{"css":"div.list > ul > li:nth-child(3)","chain":["css"]}},
               {"action":"input","target":{"css":"#password","chain":["css"]},"value":"Admin@123"}
             ]}""";

    
    private static final String CLEAN = """
            {"version":"v2","setup":[],
             "steps":[
               {"action":"goto","description":"打开登录页","target":{"url":"{{ENV:BASE}}/login"}},
               {"action":"wait","description":"等待用户名框可见","target":{"css":"#username","chain":["css"]},"state":"visible"},
               {"action":"input","description":"输入账号","target":{"id":"username","chain":["css","text"]},"value":"{{DATA:user}}"},
               {"action":"expect","description":"断言登录成功提示","target":{"text":"欢迎"},"value":"欢迎"}
             ]}""";

    private final ConfigCacheHolder configCacheHolder = new ConfigCacheHolder();
    private final ScriptStyleGate gate =
            new ScriptStyleGate(new SystemConfigReader(configCacheHolder));

    @Test
    void codegen来源开关开_违规保存被拒且逐条点名() {
        configCacheHolder.put(KEY, "1");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> gate.check(2, VIOLATING));
        String msg = ex.getMessage();
        assertTrue(msg.contains("硬等待"), "未点名硬等待：" + msg);
        assertTrue(msg.contains("nth-child") || msg.contains("位置序号"), "未点名脆弱定位：" + msg);
        assertTrue(msg.contains("明文"), "未点名明文凭据：" + msg);
        assertTrue(msg.contains("第 2 步"), "未带步号：" + msg);
    }

    @Test
    void 非codegen来源_开关开也零触发() {
        configCacheHolder.put(KEY, "1");
        assertDoesNotThrow(() -> gate.check(1, VIOLATING));
        assertDoesNotThrow(() -> gate.check(3, VIOLATING));
        assertDoesNotThrow(() -> gate.check(4, VIOLATING));
    }

    @Test
    void 开关缺省或关_codegen违规直通存量零变化() {
        assertDoesNotThrow(() -> gate.check(2, VIOLATING));
        configCacheHolder.put(KEY, "0");
        assertDoesNotThrow(() -> gate.check(2, VIOLATING));
    }

    @Test
    void 过闸脚本直通零改动() {
        configCacheHolder.put(KEY, "1");
        assertDoesNotThrow(() -> gate.check(2, CLEAN));
    }
}
