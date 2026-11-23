package com.sixbean.testpike.core.mock.domain.service;

import com.sixbean.testpike.core.mock.api.dto.MockScenarioInfo;
import com.sixbean.testpike.core.mock.api.dto.MockScenarioInput;
import com.sixbean.testpike.core.mock.api.dto.MockScenarioPageView;

import java.util.List;

/**
 * Stateful scenario declaration service . Owns the machine
 * definitions of tp_mock_scenario — the definition truth stays in PostgreSQL
 * while runtime session→state lives in Redis (D-c ruling), so this plane never
 * touches runtime state.
 */
public interface MockScenarioService {

    /** File a machine declaration under a space (save-chain single point). */
    long create(long spaceId, MockScenarioInput input);

    /** One scenario as saved (unknown id = plain-word refusal). */
    MockScenarioInfo get(long id);

    /** Scenarios of one space in name order. */
    List<MockScenarioInfo> listBySpace(long spaceId);

    /**
 * One paged window of the space's scenarios, name order kept per page
 * display-scale iron law: the list page must consume a
 * bounded window instead of dumping every machine of a heavy space).
 * Historical callers keep this name-first window untouched.
 */
    MockScenarioPageView listBySpacePaged(long spaceId, int page, int pageSize);

    /**
 * Sorting form : a whitelisted sortBy/sortDir pair orders
 * the window in core SQL (physical columns plus scope and the counted
 * states/transitions jsonb facets); null/blank keeps the historical name
 * order. Off-list keys are refused fail-closed by the shared gate.
 */
    MockScenarioPageView listBySpacePaged(long spaceId, int page, int pageSize,
                                          String sortBy, String sortDir);
}
