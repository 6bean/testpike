package com.sixbean.testpike.admin.token.infrastructure.remote.client;

import com.sixbean.testpike.admin.token.api.dto.ApiTokenCreateRequest;
import com.sixbean.testpike.admin.token.api.dto.ApiTokenCreatedView;
import com.sixbean.testpike.admin.token.api.dto.ApiTokenUsageView;
import com.sixbean.testpike.admin.token.api.dto.ApiTokenView;

import java.util.List;


public interface ApiTokenClient {

    List<ApiTokenView> listByUser(long userId);

    ApiTokenCreatedView create(long userId, ApiTokenCreateRequest request);

    void revoke(long id, long operatorId);

    /** GA3 usage roll-up (core /internal/mcp-tokens/{id}/usage; owner ∪ admin read-through decided once in core). */
    ApiTokenUsageView usage(long tokenId, long operatorId);
}
