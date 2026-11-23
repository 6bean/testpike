package com.sixbean.testpike.core.spec.api.dto;

import java.util.List;

/**
 * Disposition request body for the contract change queue (core internal).
 *
 * @param ids  checked queue row ids (batch endpoint only; single-row path uses the id
 *             from the URL)
 * @param note disposition note — required, plain-language review conclusion
 */
public record SpecChangeHandleRequest(List<Long> ids, String note) {
}
