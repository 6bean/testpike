package com.sixbean.testpike.core.webelement.api.controller;

import com.sixbean.testpike.core.webelement.api.dto.SiteProfileInput;
import com.sixbean.testpike.core.webelement.api.dto.SiteProfileView;
import com.sixbean.testpike.core.webelement.domain.service.SiteProfileService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Scripted stand-in for the site-profile service used by the thin internal
 * door test: it records which door fired with which token so the test can
 * assert the controller holds no rule of its own.
 */
final class RecordingSiteProfileService implements SiteProfileService {

    final List<String> calls = new ArrayList<>();
    SiteProfileInput lastCreate;
    long lastListProject;
    long lastUpdateId;
    SiteProfileInput lastUpdatePayload;
    long lastDeleteId;

    @Override
    public SiteProfileView create(SiteProfileInput input) {
        calls.add("create");
        lastCreate = input;
        return row();
    }

    @Override
    public SiteProfileView update(long id, SiteProfileInput input) {
        calls.add("update");
        lastUpdateId = id;
        lastUpdatePayload = input;
        return row();
    }

    @Override
    public SiteProfileView get(long id) {
        calls.add("get");
        return row();
    }

    @Override
    public List<SiteProfileView> list(long projectId) {
        calls.add("list");
        lastListProject = projectId;
        return List.of(row());
    }

    @Override
    public void delete(long id) {
        calls.add("delete");
        lastDeleteId = id;
    }

    private static SiteProfileView row() {
        return new SiteProfileView(3L, 7L, "商城主站", "https://xx.com/pr",
                "/pr/", List.of(), List.of("page"), true, Map.of(),
                "登录态A", true, 0, LocalDateTime.now());
    }
}
