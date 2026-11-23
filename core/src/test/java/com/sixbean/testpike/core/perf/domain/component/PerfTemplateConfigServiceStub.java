package com.sixbean.testpike.core.perf.domain.component;

import com.sixbean.testpike.core.config.api.dto.ConfigInfo;
import com.sixbean.testpike.core.config.api.dto.ConfigInput;
import com.sixbean.testpike.core.config.domain.service.ConfigService;

import java.util.ArrayList;
import java.util.List;

/**
 * Group-aware in-memory ConfigService for L2 isolation (CF-4 template catalogue
 * reads through {@code list(groupName)}). Same posture as the apitest preset
 * stub: the catalogue needs the group query, the executor-side value-only stub
 * cannot serve it, so this perf-domain test fixture implements the read paths
 * against a fixed row list and refuses the write paths. Infrastructure
 * isolation only — the catalogue's own judgement stays real code.
 */
class PerfTemplateConfigServiceStub implements ConfigService {

    private final List<ConfigInfo> rows;

    PerfTemplateConfigServiceStub(List<ConfigInfo> rows) {
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
