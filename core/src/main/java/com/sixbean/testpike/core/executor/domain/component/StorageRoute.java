package com.sixbean.testpike.core.executor.domain.component;

import com.sixbean.testpike.common.storage.StorageProvider;


public record StorageRoute(StorageProvider provider, String prefix) {
}
