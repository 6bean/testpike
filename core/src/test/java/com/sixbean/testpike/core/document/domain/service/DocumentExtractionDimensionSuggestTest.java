package com.sixbean.testpike.core.document.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.api.dto.ConfigInput;
import com.sixbean.testpike.core.document.api.dto.ExtractionItem;
import com.sixbean.testpike.core.document.api.dto.ExtractionPreview;
import com.sixbean.testpike.core.document.domain.component.DocDimensionSuggester;
import com.sixbean.testpike.core.document.domain.service.impl.DocumentExtractionServiceImpl;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = {"testpike.scheduling.enabled=false"})
class DocumentExtractionDimensionSuggestTest {

    @Autowired
    private DocumentExtractionService extractionService;
    @Autowired
    private DocumentService documentService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private com.sixbean.testpike.core.config.domain.service.ConfigService configService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long projectId;
    private Long docId;

    
    private static final String DIMENSION_SWITCH_DESCRIPTION =
            "需求点提取预览的维度建议开关（默认开启；开启时预览返回尚未被条目覆盖的维度建议；"
                    + "关闭时不再返回建议，提取与入库流程不受影响。修改即时生效，无需重启）";

    @AfterEach
    void cleanUpFixtures() {
        ExtractionPreviewFixtures.cleanDocsFixtures(jdbcTemplate, projectId, docId);
        docId = null;
        projectId = null;
        Long id = jdbcTemplate.query(
                "SELECT id FROM tp_sys_config WHERE config_key = ?",
                rs -> rs.next() ? rs.getLong(1) : null,
                DocumentExtractionServiceImpl.KEY_DIMENSION_SUGGEST);
        if (id != null) {
            configService.update(1L, id, new ConfigInput(
                    DocumentExtractionServiceImpl.KEY_DIMENSION_SUGGEST, "true",
                    "系统", "bool", 0, DIMENSION_SWITCH_DESCRIPTION));
        }
    }

    
    private long preparingDoc(String token) {
        ExtractionPreviewFixtures.Docs docs = ExtractionPreviewFixtures.preparingDoc(
                "维度建议项目", projectService, documentService, jdbcTemplate);
        projectId = docs.projectId();
        docId = docs.docId();
        ExtractionPreviewFixtures.armExtracting(jdbcTemplate, docId, token);
        return docId;
    }

    
    private static List<ExtractionItem> functionalOnlyItems() {
        return ExtractionPreviewFixtures.functionalOnlyItems();
    }

    private void upsertSwitch(String value) {
        Long id = jdbcTemplate.query(
                "SELECT id FROM tp_sys_config WHERE config_key = ?",
                rs -> rs.next() ? rs.getLong(1) : null,
                DocumentExtractionServiceImpl.KEY_DIMENSION_SUGGEST);
        ConfigInput input = new ConfigInput(
                DocumentExtractionServiceImpl.KEY_DIMENSION_SUGGEST, value, "系统", "bool", 0,
                DIMENSION_SWITCH_DESCRIPTION);
        if (id != null) {
            configService.update(1L, id, input);
        } else {
            configService.create(1L, input);
        }
    }

    @Test
    void missingDimensionsSuggestedWhenNothingCoversThem() {
        String token = UUID.randomUUID().toString();
        long id = preparingDoc(token);
        extractionService.writeExtractionResult(id, functionalOnlyItems(), token);

        ExtractionPreview preview = extractionService.extraction(id, 1, 10);
        assertEquals(List.of(DocDimensionSuggester.EXCEPTION_FLOW,
                DocDimensionSuggester.BOUNDARY,
                DocDimensionSuggester.NON_FUNCTIONAL), preview.dimensionSuggestions(),
                "缺行=出厂开：功能型清单应建议全部三个维度");
    }

    @Test
    void coveredDimensionsDropOutOfSuggestions() {
        String token = UUID.randomUUID().toString();
        long id = preparingDoc(token);
        extractionService.writeExtractionResult(id, List.of(
                new ExtractionItem(0, "库存不足下单失败提示", "提交订单返回库存不足人话",
                        "下单流程", 0.9, false, null),
                new ExtractionItem(0, "备注长度上限", "备注最多输入500字符，超长禁止提交",
                        "下单流程", 0.85, false, null)), token);

        ExtractionPreview preview = extractionService.extraction(id, 1, 10);
        assertEquals(List.of(DocDimensionSuggester.NON_FUNCTIONAL),
                preview.dimensionSuggestions(), "已呈现维度不得再建议（缺才提示）");
    }

    @Test
    void switchOff_hidesSuggestionsAndHotReloadRestores() {
        String token = UUID.randomUUID().toString();
        long id = preparingDoc(token);
        extractionService.writeExtractionResult(id, functionalOnlyItems(), token);

        upsertSwitch("false");
        assertTrue(extractionService.extraction(id, 1, 10)
                        .dimensionSuggestions().isEmpty(),
                "显式关闭后建议集为空（无外呼无提示音）");

        upsertSwitch("true");
        assertEquals(3, extractionService.extraction(id, 1, 10)
                        .dimensionSuggestions().size(),
                "热更回开即时生效，无需重启");
    }

    @Test
    void suggestionsIndependentOfPagination() {
        String token = UUID.randomUUID().toString();
        long id = preparingDoc(token);
        List<ExtractionItem> items = new java.util.ArrayList<>(functionalOnlyItems());
        items.add(new ExtractionItem(0, "并发提交冲突防护", "重复提交返回冲突错误提示",
                "下单流程", 0.8, false, null));
        extractionService.writeExtractionResult(id, items, token);

        assertEquals(List.of(DocDimensionSuggester.BOUNDARY,
                DocDimensionSuggester.NON_FUNCTIONAL),
                extractionService.extraction(id, 1, 2).dimensionSuggestions(),
                "翻页不得扰动建议集（全量口径判定）");
    }
}
