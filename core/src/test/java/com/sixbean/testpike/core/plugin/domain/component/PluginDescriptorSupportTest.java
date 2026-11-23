package com.sixbean.testpike.core.plugin.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class PluginDescriptorSupportTest {

    private final PluginDescriptorSupport support = new PluginDescriptorSupport();

    
    @Test
    void parse_legal_descriptor() {
        String yaml = """
                name: pos-retail
                industry: POS
                version: 1.0.0
                content_pack: pos-retail-pack
                default_skills:
                  - 收银主流程用例规范
                  - 支付断言规范
                param_template:
                  pos_terminal: "T01"
                project_types:
                  - pos
                """;
        Map<String, Object> manifest = support.parse(yaml);
        assertEquals("pos-retail", manifest.get("name"));
        assertEquals("POS", manifest.get("industry"));
        assertEquals("1.0.0", manifest.get("version"));
        assertEquals("pos-retail-pack", manifest.get("content_pack"));
        assertEquals(List.of("收银主流程用例规范", "支付断言规范"), manifest.get("default_skills"));
        assertEquals(Map.of("pos_terminal", "T01"), manifest.get("param_template"));
        assertEquals(List.of("pos"), manifest.get("project_types"));
    }

    
    @Test
    void parse_minimal_descriptor_optional_keys_absent() {
        Map<String, Object> manifest = support.parse(
                "name: erp-supply\nindustry: ERP\nversion: 0.1.0\n");
        assertEquals("erp-supply", manifest.get("name"));
        assertTrue(!manifest.containsKey("default_skills")
                        || manifest.get("default_skills") == null
                        || ((List<?>) manifest.get("default_skills")).isEmpty(),
                "缺省可选键应为空/不存在");
    }

    
    @Test
    void reject_malformed_yaml() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> support.parse("name: [unclosed\n  bad: : :\t\t- x"));
        assertEquals(BizErrorCode.VALIDATION_ERROR, e.getCode());
        assertTrue(e.getMessage().contains("YAML"), () -> "消息需人话点明 YAML 问题，实际=" + e.getMessage());
    }

    
    @Test
    void reject_non_mapping_document() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> support.parse("just a plain text line"));
        assertEquals(BizErrorCode.VALIDATION_ERROR, e.getCode());
        assertTrue(e.getMessage().contains("YAML"), () -> "实际=" + e.getMessage());
    }

    
    @Test
    void reject_oversize_descriptor() {
        String big = "name: x\npadding: \"" + "a".repeat(64 * 1024) + "\"\n";
        ServiceException e = assertThrows(ServiceException.class, () -> support.parse(big));
        assertEquals(BizErrorCode.VALIDATION_ERROR, e.getCode());
        assertTrue(e.getMessage().contains("64KB"), () -> "实际=" + e.getMessage());
    }

    
    @Test
    void reject_missing_required_keys() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> support.parse("name: only-name\nindustry: POS\n"));
        assertEquals(BizErrorCode.VALIDATION_ERROR, e.getCode());
        assertTrue(e.getMessage().contains("version"), () -> "需点名缺失键，实际=" + e.getMessage());
    }

    
    @Test
    void reject_unknown_key() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> support.parse("name: n\nindustry: POS\nversion: 1\nauthor: hacker\n"));
        assertEquals(BizErrorCode.VALIDATION_ERROR, e.getCode());
        assertTrue(e.getMessage().contains("author"), () -> "需点名未知键，实际=" + e.getMessage());
        assertTrue(e.getMessage().contains("name"), () -> "需列合法键集合，实际=" + e.getMessage());
    }

    
    @Test
    void reject_executable_field() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> support.parse("name: n\nindustry: POS\nversion: 1\nexecutable: rm -rf /\n"));
        assertEquals(BizErrorCode.VALIDATION_ERROR, e.getCode());
        assertTrue(e.getMessage().contains("禁"), () -> "安全底线需人话点明禁止，实际=" + e.getMessage());
        assertTrue(e.getMessage().contains("executable"), () -> "实际=" + e.getMessage());
    }

    
    @Test
    void reject_script_field_variants() {
        assertThrows(ServiceException.class,
                () -> support.parse("name: n\nindustry: POS\nversion: 1\nhooks:\n  script: evil\n"));
    }

    
    @Test
    void reject_wrong_types() {
        ServiceException e1 = assertThrows(ServiceException.class,
                () -> support.parse("name: n\nindustry: POS\nversion: 1\ndefault_skills: not-a-list\n"));
        assertTrue(e1.getMessage().contains("default_skills"), () -> "实际=" + e1.getMessage());
        ServiceException e2 = assertThrows(ServiceException.class,
                () -> support.parse("name: n\nindustry: POS\nversion: 1\nparam_template: [1, 2]\n"));
        assertTrue(e2.getMessage().contains("param_template"), () -> "实际=" + e2.getMessage());
    }

    
    @Test
    void reject_blank_content() {
        assertThrows(ServiceException.class, () -> support.parse("   \n  "));
        assertThrows(ServiceException.class, () -> support.parse(null));
    }
}
