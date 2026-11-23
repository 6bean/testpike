package com.sixbean.testpike.admin.spec.api.dto;

import java.util.List;

/**
 * Disposition request for the contract change queue (admin external body).
 *
 * @param ids  checked row ids (batch path; single-row path takes the id from the URL)
 * @param note plain-language review conclusion — required (core enforces the trace)
 */
public record SpecChangeHandleRequest(List<Long> ids, String note) {
}
