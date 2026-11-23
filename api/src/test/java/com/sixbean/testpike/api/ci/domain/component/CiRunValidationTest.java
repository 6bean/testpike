package com.sixbean.testpike.api.ci.domain.component;

import com.sixbean.testpike.api.ci.api.dto.CiRunInput;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class CiRunValidationTest {

    private final CiRunValidator validator = new CiRunValidator();

    @Test
    void batch链路_合法通过() {
        validator.validate(new CiRunInput(7L, null, null, null,
                Map.of("url", "https://ci.example.com")));
    }

    @Test
    void profile链路_带项目与环境合法通过() {
        validator.validate(new CiRunInput(null, "冒烟模板", 3L, 5L, null));
    }

    @Test
    void batch与profile双缺_400人话() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> validator.validate(new CiRunInput(null, null, null, null, null)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("二选一"), ex.getMessage());
        assertTrue(ex.getMessage().contains("batch") && ex.getMessage().contains("profile"),
                "人话须点名两个字段: " + ex.getMessage());
    }

    @Test
    void batch与profile双有_400指引消歧() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> validator.validate(new CiRunInput(7L, "冒烟模板", 3L, null, null)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("二选一"), ex.getMessage());
    }

    @Test
    void profile链路缺projectId_400人话() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> validator.validate(new CiRunInput(null, "冒烟模板", null, 5L, null)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("projectId"), ex.getMessage());
    }

    @Test
    void envOverrides未知键_400列出合法键集() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> validator.validate(new CiRunInput(7L, null, null, null,
                        Map.of("pass", "hunter2"))));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("url") && ex.getMessage().contains("accountRef"),
                "必须列出合法键: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("pass"), "必须点名非法键: " + ex.getMessage());
    }

    @Test
    void envOverrides两合法键_通过() {
        validator.validate(new CiRunInput(7L, null, null, null,
                Map.of("url", "https://ci.example.com", "accountRef", "acct-ref-1")));
    }
}
