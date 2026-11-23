package com.sixbean.testpike.core.batch.domain.component;

import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


class BatchParamSnapshotTtlFallbackTest {

    private BatchParamSnapshotStore storeWith(ConfigCacheHolder holder) {
        return new BatchParamSnapshotStore(null, null, holder, null);
    }

    @Test
    void 键缺失_回退默认24小时() {
        ConfigCacheHolder holder = mock(ConfigCacheHolder.class);
        when(holder.get(BatchParamSnapshotStore.TTL_KEY)).thenReturn(null);
        assertEquals(24L, storeWith(holder).ttlHours(), "键缺失必须=旧默认 24h（零漂移）");
    }

    @Test
    void 键空白或非法_回退默认24小时() {
        ConfigCacheHolder holder = mock(ConfigCacheHolder.class);
        BatchParamSnapshotStore store = storeWith(holder);
        when(holder.get(BatchParamSnapshotStore.TTL_KEY)).thenReturn("  ");
        assertEquals(24L, store.ttlHours(), "空白值回退默认");
        when(holder.get(BatchParamSnapshotStore.TTL_KEY)).thenReturn("abc");
        assertEquals(24L, store.ttlHours(), "非法值回退默认");
        when(holder.get(BatchParamSnapshotStore.TTL_KEY)).thenReturn("0");
        assertEquals(24L, store.ttlHours(), "非正值回退默认（禁 0=TTL 立即过期断链）");
        when(holder.get(BatchParamSnapshotStore.TTL_KEY)).thenReturn("-3");
        assertEquals(24L, store.ttlHours(), "负值回退默认");
    }

    @Test
    void 键正数_热更即生效精确值() {
        ConfigCacheHolder holder = mock(ConfigCacheHolder.class);
        BatchParamSnapshotStore store = storeWith(holder);
        when(holder.get(BatchParamSnapshotStore.TTL_KEY)).thenReturn("6");
        assertEquals(6L, store.ttlHours(), "K8 键改 6 → TTL 必现读 6（热更即生效）");
        when(holder.get(BatchParamSnapshotStore.TTL_KEY)).thenReturn(" 72 ");
        assertEquals(72L, store.ttlHours(), "带空白正数解析成功（容错 trim）");
    }
}
