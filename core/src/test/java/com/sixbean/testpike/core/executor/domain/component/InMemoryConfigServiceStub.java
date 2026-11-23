package com.sixbean.testpike.core.executor.domain.component;

import com.sixbean.testpike.core.config.api.dto.ConfigInfo;
import com.sixbean.testpike.core.config.api.dto.ConfigInput;
import com.sixbean.testpike.core.config.domain.service.ConfigService;

import java.util.List;
import java.util.Map;


public class InMemoryConfigServiceStub implements ConfigService {

    private final Map<String, String> values;

    public InMemoryConfigServiceStub(Map<String, String> values) {
        this.values = values;
    }

    @Override
    public ConfigInfo create(Long operatorId, ConfigInput input) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ConfigInfo update(Long operatorId, long id, ConfigInput input) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void delete(Long operatorId, long id) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<ConfigInfo> list(String groupName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getValue(String configKey, String defaultValue) {
        return values.getOrDefault(configKey, defaultValue);
    }
}
