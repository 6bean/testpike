package com.sixbean.testpike.aiservice.gen.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class PromptTemplateResolverTest {

    private static final long TASK_ID = 93L;
    private static final long PROJECT_ID = 3L;
    private static final String NAME = PromptTemplateResolver.STAGE1_TEMPLATE_NAME;
    private static final String DOMAIN = PromptTemplateResolver.TEMPLATE_DOMAIN;

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final PromptTemplateResolver resolver = new PromptTemplateResolver(coreGateway);

    private static CoreGateway.PromptTemplateBrief row(long id, String content, int status) {
        return new CoreGateway.PromptTemplateBrief(id, NAME, DOMAIN, (int) id, content, status);
    }

    
    @Test
    void 任务钉住非空_按版本行直读不回拽禁重选桶() {
        when(coreGateway.getTaskTemplatePin(TASK_ID)).thenReturn(77L);
        when(coreGateway.promptTemplateDetail(77L)).thenReturn(row(77L, "【钉住版】", 3));

        Optional<CoreGateway.PromptTemplateBrief> picked =
                resolver.selectTemplate(NAME, PROJECT_ID, TASK_ID);

        assertTrue(picked.isPresent());
        assertEquals("【钉住版】", picked.get().content());
        verify(coreGateway, never()).getSysConfig(anyString());
        verify(coreGateway, never()).promptTemplateLatestByStatus(anyString(), anyString(), anyInt());
        verify(coreGateway, never()).pinTemplate(anyLong(), anyLong());
    }

    
    @Test
    void 钉住指向已删行_降级默认不回拽重选() {
        when(coreGateway.getTaskTemplatePin(TASK_ID)).thenReturn(88L);
        when(coreGateway.promptTemplateDetail(88L)).thenReturn(null);

        assertTrue(resolver.selectTemplate(NAME, PROJECT_ID, TASK_ID).isEmpty());
        verify(coreGateway, never()).pinTemplate(anyLong(), anyLong());
    }

    
    @Test
    void 灰度命中_读灰度族最新且选得即钉() {
        when(coreGateway.getTaskTemplatePin(TASK_ID)).thenReturn(null);
        when(coreGateway.getSysConfig(PromptTemplateResolver.TRAFFIC_PCT_KEY)).thenReturn("100");
        when(coreGateway.promptTemplateLatestByStatus(NAME, DOMAIN, 1))
                .thenReturn(row(21L, "【灰度版】", 1));

        Optional<CoreGateway.PromptTemplateBrief> picked =
                resolver.selectTemplate(NAME, PROJECT_ID, TASK_ID);

        assertTrue(picked.isPresent());
        assertEquals("【灰度版】", picked.get().content());
        verify(coreGateway).promptTemplateLatestByStatus(NAME, DOMAIN, 1);
        verify(coreGateway).pinTemplate(TASK_ID, 21L);
    }

    
    @Test
    void 未命中灰度_读全量族最新且选得即钉() {
        when(coreGateway.getTaskTemplatePin(TASK_ID)).thenReturn(null);
        when(coreGateway.getSysConfig(PromptTemplateResolver.TRAFFIC_PCT_KEY)).thenReturn("0");
        when(coreGateway.promptTemplateLatestByStatus(NAME, DOMAIN, 2))
                .thenReturn(row(22L, "【全量版】", 2));

        Optional<CoreGateway.PromptTemplateBrief> picked =
                resolver.selectTemplate(NAME, PROJECT_ID, TASK_ID);

        assertTrue(picked.isPresent());
        assertEquals("【全量版】", picked.get().content());
        verify(coreGateway, never()).promptTemplateLatestByStatus(NAME, DOMAIN, 1);
        verify(coreGateway).pinTemplate(TASK_ID, 22L);
    }

    
    @Test
    void 流量键非法_按零处理走全量桶() {
        when(coreGateway.getTaskTemplatePin(TASK_ID)).thenReturn(null);
        when(coreGateway.getSysConfig(PromptTemplateResolver.TRAFFIC_PCT_KEY)).thenReturn("abc");
        when(coreGateway.promptTemplateLatestByStatus(NAME, DOMAIN, 2))
                .thenReturn(row(23L, "【全量版】", 2));

        assertEquals("【全量版】",
                resolver.selectTemplate(NAME, PROJECT_ID, TASK_ID).orElseThrow().content());
    }

    
    @Test
    void 两桶皆空_回退内置默认且不写钉() {
        when(coreGateway.getTaskTemplatePin(TASK_ID)).thenReturn(null);
        when(coreGateway.getSysConfig(PromptTemplateResolver.TRAFFIC_PCT_KEY)).thenReturn("50");
        when(coreGateway.promptTemplateLatestByStatus(anyString(), anyString(), anyInt()))
                .thenReturn(null);

        assertTrue(resolver.selectTemplate(NAME, PROJECT_ID, TASK_ID).isEmpty());
        verify(coreGateway, never()).pinTemplate(anyLong(), anyLong());
    }

    
    @Test
    void 灰度命中但灰度族空_全量接续并钉() {
        when(coreGateway.getTaskTemplatePin(TASK_ID)).thenReturn(null);
        when(coreGateway.getSysConfig(PromptTemplateResolver.TRAFFIC_PCT_KEY)).thenReturn("100");
        when(coreGateway.promptTemplateLatestByStatus(NAME, DOMAIN, 1)).thenReturn(null);
        when(coreGateway.promptTemplateLatestByStatus(NAME, DOMAIN, 2))
                .thenReturn(row(24L, "【全量版】", 2));

        assertEquals("【全量版】",
                resolver.selectTemplate(NAME, PROJECT_ID, TASK_ID).orElseThrow().content());
        verify(coreGateway).pinTemplate(TASK_ID, 24L);
    }

    
    @Test
    void 钉住写路径异常_选中仍返回不炸生成() {
        when(coreGateway.getTaskTemplatePin(TASK_ID)).thenReturn(null);
        when(coreGateway.getSysConfig(PromptTemplateResolver.TRAFFIC_PCT_KEY)).thenReturn("0");
        when(coreGateway.promptTemplateLatestByStatus(NAME, DOMAIN, 2))
                .thenReturn(row(25L, "【全量版】", 2));
        org.mockito.Mockito.doThrow(new RuntimeException("core 写路径不可达模拟"))
                .when(coreGateway).pinTemplate(anyLong(), anyLong());

        assertEquals("【全量版】",
                resolver.selectTemplate(NAME, PROJECT_ID, TASK_ID).orElseThrow().content());
    }

    
    @Test
    void 读路径异常_降级empty不抛() {
        when(coreGateway.getTaskTemplatePin(anyLong()))
                .thenThrow(new RuntimeException("core 不可达模拟"));

        assertTrue(resolver.selectTemplate(NAME, PROJECT_ID, TASK_ID).isEmpty());

        org.mockito.Mockito.reset(coreGateway);
        when(coreGateway.getTaskTemplatePin(TASK_ID)).thenReturn(null);
        when(coreGateway.getSysConfig(PromptTemplateResolver.TRAFFIC_PCT_KEY)).thenReturn("100");
        when(coreGateway.promptTemplateLatestByStatus(eq(NAME), eq(DOMAIN), eq(1)))
                .thenThrow(new RuntimeException("桶读路径抖动"));
        assertTrue(resolver.selectTemplate(NAME, PROJECT_ID, TASK_ID).isEmpty());
    }

    
    @Test
    void 每生成即读_二次选择现读禁缓存() {
        when(coreGateway.getTaskTemplatePin(TASK_ID)).thenReturn(null);
        when(coreGateway.getSysConfig(PromptTemplateResolver.TRAFFIC_PCT_KEY)).thenReturn("0");
        when(coreGateway.promptTemplateLatestByStatus(NAME, DOMAIN, 2))
                .thenReturn(row(26L, "【全量版】", 2));

        resolver.selectTemplate(NAME, PROJECT_ID, TASK_ID);
        resolver.selectTemplate(NAME, PROJECT_ID, TASK_ID);

        verify(coreGateway, times(2)).promptTemplateLatestByStatus(NAME, DOMAIN, 2);
    }
}
