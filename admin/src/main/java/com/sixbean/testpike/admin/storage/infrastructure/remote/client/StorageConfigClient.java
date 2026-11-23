package com.sixbean.testpike.admin.storage.infrastructure.remote.client;

import com.sixbean.testpike.admin.storage.api.dto.StorageConfigSaveRequest;
import com.sixbean.testpike.admin.storage.api.dto.StorageConfigView;
import com.sixbean.testpike.admin.storage.api.dto.StorageProbeRequest;
import com.sixbean.testpike.admin.storage.api.dto.StorageProbeResult;


public interface StorageConfigClient {

    StorageConfigView get();

    StorageConfigView update(StorageConfigSaveRequest request);

    StorageProbeResult probe(StorageProbeRequest request);
}
