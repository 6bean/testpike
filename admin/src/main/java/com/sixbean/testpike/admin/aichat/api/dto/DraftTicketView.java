package com.sixbean.testpike.admin.aichat.api.dto;

/**
 * Draft confirmation ticket crossing the admin boundary (AO batch stage
 * 3b , generalized in stage 1; mirror of the core ticket, dock
 * countdown source).
 */
public record DraftTicketView(String token, long expiresInSeconds) {
}
