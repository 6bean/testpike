package com.sixbean.testpike.core.mock.domain.service.impl;

import com.sixbean.testpike.core.mock.api.dto.MockStubInput;
import com.sixbean.testpike.core.mock.api.dto.MockStubImportResult;
import com.sixbean.testpike.core.mock.api.dto.MockStubInfo;
import com.sixbean.testpike.core.mock.domain.component.MockRuleKeys;
import com.sixbean.testpike.core.mock.domain.component.MockStubDraft;
import com.sixbean.testpike.core.mock.domain.component.StubDraftMapper;
import com.sixbean.testpike.core.mock.domain.service.MockStubImportService;
import com.sixbean.testpike.core.mock.domain.service.MockStubService;
import com.sixbean.testpike.core.spec.api.dto.ApiSpecInfo;
import com.sixbean.testpike.core.spec.domain.component.ApiEndpoint;
import com.sixbean.testpike.core.spec.domain.component.ParsedApiSpec;
import com.sixbean.testpike.core.spec.domain.service.ApiSpecService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Contract-direct import implementation .
 * <p>Preview reads the endpoint snapshot already stored on the tp_api_spec
 * version row (the parser is never re-run, so a draft always reflects exactly
 * what was recorded at upload time) and replays it through StubDraftMapper —
 * deterministic, zero LLM tokens.
 * <p>Import delegates every row to {@link MockStubService#create}, i.e. the
 * ordinary stub save chain with its MockRuleValidator single point: the import
 * adds no gate of its own (red line 6 — one rule source, one validator). The
 * only extra behaviour is idempotence: a space that already carries the same
 * method+path rule keeps it untouched instead of gaining a twin, which makes
 * re-importing an upgraded contract safe.
 */
@Service
public class MockStubImportServiceImpl implements MockStubImportService {

    /** Hot-update knob: how deep response schemas unfold into sample bodies. */
    static final String KEY_EXPAND_MAX_DEPTH = "mock.spec-import.schema-expand-max-depth";

    private final ApiSpecService apiSpecService;
    private final MockStubService stubService;
    private final com.sixbean.testpike.core.config.domain.service.ConfigService configService;
    private final StubDraftMapper mapper = new StubDraftMapper();

    public MockStubImportServiceImpl(ApiSpecService apiSpecService, MockStubService stubService,
                                     com.sixbean.testpike.core.config.domain.service.ConfigService
                                             configService) {
        this.apiSpecService = apiSpecService;
        this.stubService = stubService;
        this.configService = configService;
    }

    @Override
    public List<MockStubDraft> previewDrafts(long specId) {
        ApiSpecInfo spec = apiSpecService.get(specId);
        List<ApiEndpoint> endpoints = spec.endpoints() == null ? List.of() : spec.endpoints();
        return mapper.fromParsedSpec(specId,
                new ParsedApiSpec(spec.specFormat(), spec.specTitle(),
                        String.valueOf(spec.specVersion()), endpoints),
                expandMaxDepth());
    }

    /** Configured expansion depth, shipped default on blank/garbage/foreign values. */
    private int expandMaxDepth() {
        String raw = configService.getValue(KEY_EXPAND_MAX_DEPTH,
                String.valueOf(StubDraftMapper.DEFAULT_EXPAND_MAX_DEPTH));
        try {
            int parsed = Integer.parseInt(raw.trim());
            return Math.min(64, Math.max(1, parsed));
        } catch (Exception e) {
            return StubDraftMapper.DEFAULT_EXPAND_MAX_DEPTH;
        }
    }

    @Override
    @Transactional
    public MockStubImportResult importDrafts(long spaceId, List<MockStubDraft> drafts) {
        Set<String> covered = coveredKeys(spaceId);
        List<Long> created = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        if (drafts == null) {
            return new MockStubImportResult(created, skipped);
        }
        for (MockStubDraft draft : drafts) {
            String key = ruleKey(draft.method(), draft.pathPattern());
            if (covered.contains(key)) {
                skipped.add(draft.endpointKey());
                continue;
            }
            long stubId = stubService.create(spaceId, toInput(draft));
            covered.add(key);
            created.add(stubId);
        }
        return new MockStubImportResult(created, skipped);
    }

    /**
     * method+path pairs the space already serves. Two drafts of the same key
     * inside one import are also collapsed here (the second one skips), so a
     * contract that declares the same operation twice files one rule.
     */
    private Set<String> coveredKeys(long spaceId) {
        Set<String> keys = new HashSet<>();
        for (MockStubInfo stub : stubService.listBySpace(spaceId)) {
            keys.add(ruleKey(stub.method(), stub.pathPattern()));
        }
        return keys;
    }

    private String ruleKey(String method, String pathPattern) {
        return MockRuleKeys.of(method, pathPattern);
    }

    private MockStubInput toInput(MockStubDraft draft) {
        return new MockStubInput(draft.name(), null, draft.method(), draft.pathMatchType(),
                draft.pathPattern(), draft.matchersJson(), draft.responseJson(), null, null, null,
                draft.source(), draft.sourceRef(), Boolean.TRUE, null, draft.enrichedBy());
    }
}
