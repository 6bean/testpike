package com.sixbean.testpike.aiservice.devtoolspanel.api.dto;

/**
 * One structured login-state row recovered from a DevTools Application panel
 * screenshot . {@code kind} is {@code cookie} or
 * {@code local_storage}; {@code origin} carries the cookie Domain column for
 * cookies and the panel origin URL for storage entries; {@code expiresAt}
 * keeps the raw column text ("Session" is a normal value, parsing happens at
 * registration time). {@code annotated} carries the piece C red-mark flag: the
 * draft-card selection (piece D) pre-checks marked rows, and when nothing in
 * the shot is marked the full candidate set is offered unchecked-by-default.
 */
public record LoginStateCandidate(String kind, String name, String value,
                                  String origin, String expiresAt, boolean httpOnly,
                                  boolean annotated) {
}
