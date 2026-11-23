package com.sixbean.testpike.aiservice.llm.pipeline;

import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L2 contract for the pre-send reasoning-input redaction gate (behaviour
 * kernel second round, item 2): every reasoning payload leaves the system
 * through one masking source — credential / identity forms are replaced
 * before the provider ever sees them, ordinary requirement text passes
 * untouched (the vocabulary is deliberately narrow), and a closed gate never
 * silently raw-sends detected sensitive text: it refuses with the unified
 * switched-off guidance frame naming the settings path and the config key.
 */
class RedactGateTest {

    @Test
    void credentialFormsAreMaskedBeforeSend() {
        String payload = """
                登录接口联调资料：sk-proj-QF3mBx7vN2rT8wY1cZ6dK9aL 备用 AKIAIOSFODNN7EXAMPLE
                测试账号 password=Adm1nPass99 会话 token=eyJhbGciOiJIUzI1NiIs.rL0k.pQ2""";
        String masked = RedactGate.redactOutbound(null, null, payload);
        assertFalse(masked.contains("sk-proj-QF3mBx7vN2rT8wY1cZ6dK9aL"), "api-key 形态必须遮蔽");
        assertFalse(masked.contains("AKIAIOSFODNN7EXAMPLE"), "云密钥形态必须遮蔽");
        assertFalse(masked.contains("Adm1nPass99"), "密钥赋值形态的值必须遮蔽");
        assertFalse(masked.contains("eyJhbGciOiJIUzI1NiIs.rL0k.pQ2"), "token 赋值形态的值必须遮蔽");
        assertTrue(masked.contains(RedactGate.MASK_MARK), "遮蔽后必须留下可见的脱敏标记");
        assertTrue(masked.contains("登录接口联调资料"), "非敏感词必须原样保留（宁窄勿宽）");
    }

    @Test
    void identityNumberFormsAreMaskedBeforeSend() {
        String payload = "操作员登记 110101199003078515，备用手机 13800138000 与座机 021-54321000";
        String masked = RedactGate.redactOutbound(null, null, payload);
        assertFalse(masked.contains("110101199003078515"), "身份证形态必须遮蔽");
        assertFalse(masked.contains("13800138000"), "手机号形态必须遮蔽");
        assertTrue(masked.contains("操作员登记"), "上下文文字必须保留");
    }

    @Test
    void ordinaryRequirementTextPassesUntouched() {
        String payload = "需求：用户名密码长度 6-20 位，密码需包含大小写字母与数字；"
                + "手机号字段做 11 位格式校验；接口 token 机制按现行框架文档执行。";
        assertEquals(payload, RedactGate.redactOutbound(null, null, payload),
                "正常需求文本零误伤（词形集宁窄勿宽）");
    }

    @Test
    void closedGateRefusesSensitivePayloadWithUnifiedGuidance() {
        String payload = "把这份配置的 admin password=Adm1nPass99 发给模型解释一下";
        ServiceException refusal = assertThrows(ServiceException.class,
                () -> RedactGate.redactOutbound("false", null, payload));
        String message = String.valueOf(refusal.getMessage());
        assertTrue(message.contains("ai.llm.redact-enabled"),
                "关闭态指路必须点名参数名称字面: " + message);
        assertTrue(message.contains("系统管理 → 系统配置"),
                "关闭态指路必须点名打开位置字面: " + message);
    }

    @Test
    void closedGatePassesCleanPayloadRaw() {
        String payload = "为登录功能生成一批回归用例";
        assertEquals(payload, RedactGate.redactOutbound("false", null, payload));
    }

    @Test
    void gateDefaultsOpenOnMissingOrBlankSwitch() {
        assertTrue(RedactGate.gateOpen(null));
        assertTrue(RedactGate.gateOpen("  "));
        assertTrue(RedactGate.gateOpen("unknown-value"));
        assertFalse(RedactGate.gateOpen("false"));
        assertFalse(RedactGate.gateOpen("0"));
    }

    @Test
    void governanceKeyAddsHotCustomFormsAndSurvivesBadRegex() {
        String patterns = "内部代号=PRJ-\\d{4}\n坏形态=((未闭合";
        String payload = "灰度批次 PRJ-2611 的用例归属如下";
        String masked = RedactGate.redactOutbound(null, patterns, payload);
        assertFalse(masked.contains("PRJ-2611"), "热更词形必须生效");
        assertTrue(masked.contains("内部代号"), "遮蔽标记须带形态名");
        // The invalid line must not take the send path down: the good form
        // still masks while built-ins keep working alongside.
        String both = RedactGate.redactOutbound(null, patterns, "PRJ-2611 手机 13800138000");
        assertFalse(both.contains("13800138000"), "内置形态在热更配置在场时照常生效");
        assertFalse(both.contains("PRJ-2611"));
    }

    @Test
    void maskingIsIdempotentOnAlreadyMaskedText() {
        String once = RedactGate.redactOutbound(null, null, "手机 13800138000 已登记");
        String twice = RedactGate.redactOutbound(null, null, once);
        assertEquals(once, twice, "重放遮蔽不产生嵌套标记（前缀经济性）");
    }
}
