package com.sixbean.testpike.core.batch.domain.component;

import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


class BatchContextStoreTtlFallbackTest {

    private BatchContextStore storeWith(ConfigCacheHolder holder) {
        return new BatchContextStore(null, null, holder, null, null);
    }

    @Test
    void 键缺失_回退默认1800秒() {
        ConfigCacheHolder holder = mock(ConfigCacheHolder.class);
        when(holder.get(BatchContextStore.TTL_KEY)).thenReturn(null);
        assertEquals(1800L, storeWith(holder).ttlSeconds(), "键缺失必须=默认 1800s（零漂移）");
    }

    @Test
    void 键空白或非法_回退默认1800秒() {
        ConfigCacheHolder holder = mock(ConfigCacheHolder.class);
        BatchContextStore store = storeWith(holder);
        when(holder.get(BatchContextStore.TTL_KEY)).thenReturn("  ");
        assertEquals(1800L, store.ttlSeconds(), "空白值回退默认");
        when(holder.get(BatchContextStore.TTL_KEY)).thenReturn("abc");
        assertEquals(1800L, store.ttlSeconds(), "非法值回退默认");
        when(holder.get(BatchContextStore.TTL_KEY)).thenReturn("0");
        assertEquals(1800L, store.ttlSeconds(), "非正值回退默认（禁 0=TTL 立即过期断链）");
        when(holder.get(BatchContextStore.TTL_KEY)).thenReturn("-3");
        assertEquals(1800L, store.ttlSeconds(), "负值回退默认");
    }

    @Test
    void 键正数_热更即生效精确值() {
        ConfigCacheHolder holder = mock(ConfigCacheHolder.class);
        BatchContextStore store = storeWith(holder);
        when(holder.get(BatchContextStore.TTL_KEY)).thenReturn("60");
        assertEquals(60L, store.ttlSeconds(), "K8 键改 60 → TTL 必现读 60（热更即生效）");
        when(holder.get(BatchContextStore.TTL_KEY)).thenReturn(" 7200 ");
        assertEquals(7200L, store.ttlSeconds(), "带空白正数解析成功（容错 trim）");
    }
}
