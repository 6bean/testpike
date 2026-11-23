package com.sixbean.testpike.core.apitest.domain.component;

import com.sixbean.testpike.core.config.api.dto.ConfigInfo;
import com.sixbean.testpike.core.config.api.dto.ConfigInput;
import com.sixbean.testpike.core.config.domain.service.ConfigService;

import java.util.ArrayList;
import java.util.List;

/**
 * Group-aware in-memory ConfigService for L2 isolation (AuthPresetCatalog reads
 * through {@code list(groupName)}; the executor-side InMemoryConfigServiceStub
 * only answers getValue, so this stub implements the group query against a
 * fixed row list and refuses the write paths — same posture as the executor
 * stub, infrastructure isolation only, business logic stays real.
 */
class GroupRowsConfigServiceStub implements ConfigService {

    private final List<ConfigInfo> rows;

    GroupRowsConfigServiceStub(List<ConfigInfo> rows) {
        this.rows = rows;
    }

    @Override
    public List<ConfigInfo> list(String groupName) {
        if (groupName == null) {
            return List.copyOf(rows);
        }
        List<ConfigInfo> out = new ArrayList<>();
        for (ConfigInfo row : rows) {
            if (groupName.equals(row.groupName())) {
                out.add(row);
            }
        }
        return out;
    }

    @Override
    public String getValue(String configKey, String defaultValue) {
        for (ConfigInfo row : rows) {
            if (row.configKey().equals(configKey)) {
                return row.configValue();
            }
        }
        return defaultValue;
    }

    @Override
    public ConfigInfo create(Long operatorId, ConfigInput input) {
        throw new UnsupportedOperationException("catalogue reads only");
    }

    @Override
    public ConfigInfo update(Long operatorId, long id, ConfigInput input) {
        throw new UnsupportedOperationException("catalogue reads only");
    }

    @Override
    public void delete(Long operatorId, long id) {
        throw new UnsupportedOperationException("catalogue reads only");
    }
}
