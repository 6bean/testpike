package com.sixbean.testpike.admin.config.infrastructure.remote.client;

import com.sixbean.testpike.admin.config.api.dto.ConfigSaveRequest;
import com.sixbean.testpike.admin.config.api.dto.ConfigView;
import com.sixbean.testpike.admin.config.api.dto.RetentionItemView;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * In-memory ConfigClient stub for L2 bridge tests (isolates the HTTP hop to core;
 * the real chain is covered by L3/L4 scripts and E2E). Records invocations so the
 * controller delegation contract can be asserted.
 */
public class StubConfigClient implements ConfigClient {

    private final List<ConfigView> rows = new ArrayList<>();
    private final List<String> listGroupCalls = new ArrayList<>();
    private final List<Long> deleteCalls = new ArrayList<>();
    private ConfigSaveRequest lastCreate;
    private long lastUpdateId;
    private ConfigSaveRequest lastUpdate;

    public void seed(ConfigView view) {
        rows.add(view);
    }

    @Override
    public List<ConfigView> list(String groupName) {
        listGroupCalls.add(groupName);
        return List.copyOf(rows);
    }

    @Override
    public ConfigView create(ConfigSaveRequest request) {
        lastCreate = request;
        return new ConfigView(100L, request.configKey(), request.configValue(),
                request.groupName(), request.valueType(), request.encryptFlag(),
                request.description());
    }

    @Override
    public ConfigView update(long id, ConfigSaveRequest request) {
        lastUpdateId = id;
        lastUpdate = request;
        return new ConfigView(id, request.configKey(), request.configValue(),
                request.groupName(), request.valueType(), request.encryptFlag(),
                request.description());
    }

    @Override
    public void delete(long id) {
        deleteCalls.add(id);
    }

    @Override
    public List<RetentionItemView> retentionPreview() {
        return retentionItems();
    }

    @Override
    public List<RetentionItemView> retentionRun() {
        return retentionItems();
    }

    public List<String> listGroupCalls() {
        // plain copy: recorded group filters may legitimately be null
        return new ArrayList<>(listGroupCalls);
    }

    public List<Long> deleteCalls() {
        return List.copyOf(deleteCalls);
    }

    public ConfigSaveRequest lastCreate() {
        return lastCreate;
    }

    public long lastUpdateId() {
        return lastUpdateId;
    }

    public ConfigSaveRequest lastUpdate() {
        return lastUpdate;
    }

    private List<RetentionItemView> retentionItems() {
        return List.of(new RetentionItemView("retention.audit.days", "Audit log retention",
                90, LocalDateTime.of(2026, 8, 1, 3, 0), 12));
    }
}
