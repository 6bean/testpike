package com.sixbean.testpike.api.mcp.infrastructure.remote;

import java.util.List;


public record ApiTokenVerifyPayload(boolean ok, String status, String message,
                                    Long userId, Long tokenId, List<String> scopes,
                                    List<String> projectScope) {
}
