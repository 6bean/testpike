package com.sixbean.testpike.admin.aichat.infrastructure.remote.client;

import java.util.List;

/**
 * Wire body of the checked-subset door ( piece D3, mirrors the core
 * CheckedSubsetRequest): the candidate names the human ticks on a pending
 * login-state card. Names only — values never cross this boundary; the
 * landing chain reloads them from the transient capture slot core-side.
 */
public record CheckedSubsetPayload(List<String> names) {
}
