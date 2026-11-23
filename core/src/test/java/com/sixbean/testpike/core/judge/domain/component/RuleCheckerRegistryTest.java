package com.sixbean.testpike.core.judge.domain.component;

import com.sixbean.testpike.common.exception.ValidationException;
import com.sixbean.testpike.core.CoreApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class RuleCheckerRegistryTest {

    @Autowired
    private RuleCheckerRegistry registry;

    @Test
    void twelveBuiltinCheckersAreAllRegistered() {
        List<String> expected = List.of(
                "empty-output", "json-invalid", "json-array", "length-range",
                "sensitive-words", "regex-forbidden", "regex-required",
                "required-substrings", "placeholder-left", "markdown-fence-leak",
                "duplicate-lines", "truncated-tail");
        assertTrue(registry.names().containsAll(expected),
                "内置检查器清单缺项：" + expected.stream().filter(n -> !registry.names().contains(n)).toList());
        for (String name : expected) {
            assertNotNull(registry.resolve(name), "检查器应可寻址：" + name);
        }
    }

    @Test
    void unknownCheckerNameFailsClosedWithHint() {
        ValidationException e = assertThrows(ValidationException.class,
                () -> registry.resolve("no-such-checker"));
        assertTrue(e.getMessage().contains("no-such-checker"), "应点名未注册的检查器名：" + e.getMessage());
        assertTrue(e.getMessage().contains("empty-output"), "应附可用检查器清单供人话纠错：" + e.getMessage());
    }

    @Test
    void blankCheckerNameRejected() {
        assertThrows(ValidationException.class, () -> registry.resolve(null));
        assertThrows(ValidationException.class, () -> registry.resolve("  "));
    }
}
