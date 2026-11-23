package com.sixbean.testpike.core.help.domain.service;

import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.help.api.dto.HelpDocInfo;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = com.sixbean.testpike.core.CoreApplication.class)
class HelpServiceTest {

    @Autowired
    private HelpService helpService;

    @Autowired
    private ConfigService configService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbcTemplate.update("DELETE FROM tp_help_doc WHERE domain_code LIKE 'TEST%'");
    }

    private void seed(String domain, int sort, String title, String body) {
        jdbcTemplate.update(
                "INSERT INTO tp_help_doc (domain_code, title, body_md, sort_no) VALUES (?, ?, ?, ?)",
                domain, title, body, sort);
    }

    @Test
    void 章节列表域内sort升序_列表态不带正文() {
        seed("TEST", 2, "第二章", "正文二");
        seed("TEST", 1, "第一章", "正文一");
        seed("TEST", 3, "第三章", "正文三");
        seed("TEST2", 1, "另一域", "正文X");
        List<HelpDocInfo> mine = helpService.list().stream()
                .filter(d -> d.domainCode().startsWith("TEST")).toList();
        assertEquals(4, mine.size(), "TEST/TEST2 种子行全在列");
        assertEquals("TEST", mine.get(0).domainCode(), "列表按域码升序归组");
        assertEquals(List.of(1, 2, 3, 4).subList(0, 3),
                List.of(mine.get(0).sortNo(), mine.get(1).sortNo(), mine.get(2).sortNo()),
                "域内 sort_no 升序");
        assertEquals("第一章", mine.get(0).title());
        assertNull(mine.get(0).bodyMd(), "列表态不传输正文（抽屉索引只需标题）");
        assertNotNull(mine.get(0).version(), "版本随行携带（红线 8 同步条款消费面）");
    }

    @Test
    void 域详情含正文_不存在域空列表() {
        seed("TEST", 2, "第二章", "正文二\n含换行");
        seed("TEST", 1, "第一章", "正文一");
        List<HelpDocInfo> docs = helpService.detail("TEST");
        assertEquals(2, docs.size());
        assertEquals(1, docs.get(0).sortNo(), "详情同序=sort_no 升序");
        assertEquals("正文二\n含换行", docs.get(1).bodyMd());
        assertTrue(helpService.detail("NO_SUCH_DOMAIN").isEmpty(), "不存在域=空列表非异常");
    }

    @Test
    void 版本热更即时生效_空版本拒() {
        String original = configService.getValue("help.doc-version", null);
        assertNotNull(original, "V88 种子键 help.doc-version 必须在位（坑1 落库实证）");
        try {
            String updated = helpService.refreshVersion("2.41-test");
            assertEquals("2.41-test", updated);
            assertEquals("2.41-test", configService.getValue("help.doc-version", null),
                    "K8 写路径同步缓存=读侧零重启见新值（TC-K8-02 基径）");
            assertThrows(ServiceException.class, () -> helpService.refreshVersion("   "));
            assertThrows(ServiceException.class, () -> helpService.refreshVersion(null));
            assertEquals("2.41-test", configService.getValue("help.doc-version", null),
                    "400 拒绝不得半写");
        } finally {
            helpService.refreshVersion(original);
        }
        assertEquals(original, configService.getValue("help.doc-version", null), "恢复原值");
    }
}
