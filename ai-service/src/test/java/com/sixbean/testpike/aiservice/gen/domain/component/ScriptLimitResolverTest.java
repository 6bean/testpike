package com.sixbean.testpike.aiservice.gen.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


class ScriptLimitResolverTest {

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final ScriptLimitResolver resolver = new ScriptLimitResolver(coreGateway);

    @Test
    void 非数字脏值回退默认不进了prompt() {
        when(coreGateway.getSysConfig(ScriptLimitResolver.KEY_MAX_STEPS)).thenReturn("abc");
        when(coreGateway.getSysConfig(ScriptLimitResolver.KEY_MAX_LOOP)).thenReturn("-3");

        String segment = resolver.promptConstraintSegment();

        assertTrue(segment.contains("步骤 ≤50"), "非数字回退 50: " + segment);
        assertTrue(segment.contains("loop ≤20"), "负数回退 20: " + segment);
        assertFalse(segment.contains("abc"), "脏值禁进 prompt: " + segment);
    }

    @Test
    void bool键非true字面回退默认() {
        when(coreGateway.getSysConfig(ScriptLimitResolver.KEY_SMART_WAIT)).thenReturn("yes");
        when(coreGateway.getSysConfig(ScriptLimitResolver.KEY_AUTO_SCROLL)).thenReturn("TRUE");

        String segment = resolver.promptConstraintSegment();

        assertTrue(segment.contains("智能等待开启"), "非法字面回退默认 true（开启）: " + segment);
        assertTrue(segment.contains("自动滚动开启"), "大小写不敏感 TRUE=开启: " + segment);
    }

    @Test
    void 枚举字典外值回退人话默认() {
        when(coreGateway.getSysConfig(ScriptLimitResolver.KEY_SCREENSHOT_MODE)).thenReturn("9");
        when(coreGateway.getSysConfig(ScriptLimitResolver.KEY_FAIL_POLICY)).thenReturn("7");

        String segment = resolver.promptConstraintSegment();

        assertTrue(segment.contains("截图策略=仅失败"), "字典外回退 3=仅失败: " + segment);
        assertTrue(segment.contains("失败策略=继续后续步骤"), "非 2 回退 1=继续: " + segment);
    }
}
