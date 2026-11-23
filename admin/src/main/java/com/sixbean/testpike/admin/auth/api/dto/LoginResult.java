package com.sixbean.testpike.admin.auth.api.dto;

/**
 *
 * @param tokenType Bearer
 */
public record LoginResult(String accessToken, String tokenType, long expiresIn, SsoUser user,
                          String refreshToken) {
}
