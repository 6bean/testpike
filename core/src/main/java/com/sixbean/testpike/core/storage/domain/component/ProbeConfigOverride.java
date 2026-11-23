package com.sixbean.testpike.core.storage.domain.component;

import com.sixbean.testpike.core.config.api.dto.ConfigInfo;
import com.sixbean.testpike.core.config.api.dto.ConfigInput;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.config.domain.component.ConfigCipher;

import java.util.List;
import java.util.Map;


public class ProbeConfigOverride implements ConfigService {

    private final ConfigService delegate;
    private final Map<String, String> overrides;

    public ProbeConfigOverride(ConfigService delegate, Map<String, String> overrides) {
        this.delegate = delegate;
        this.overrides = overrides;
    }

    @Override
    public String getValue(String configKey, String defaultValue) {
        String override = overrides.get(configKey);
        if (override != null && !ConfigCipher.MASK.equals(override)) {
            return override;
        }
        return delegate.getValue(configKey, defaultValue);
    }

    @Override
    public ConfigInfo create(Long operatorId, ConfigInput input) {
        throw new UnsupportedOperationException("probe 零落库：拒绝写配置");
    }

    @Override
    public ConfigInfo update(Long operatorId, long id, ConfigInput input) {
        throw new UnsupportedOperationException("probe 零落库：拒绝写配置");
    }

    @Override
    public void delete(Long operatorId, long id) {
        throw new UnsupportedOperationException("probe 零落库：拒绝删配置");
    }

    @Override
    public List<ConfigInfo> list(String groupName) {
        return delegate.list(groupName);
    }
}
