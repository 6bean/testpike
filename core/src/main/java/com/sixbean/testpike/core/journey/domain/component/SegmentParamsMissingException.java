package com.sixbean.testpike.core.journey.domain.component;

/**
 * Raised when an asset-target journey segment cannot be expanded because
 * params_override_json carries no caseIds list. Caught by the run expander, which names
 * PARAMS_MISSING as the segment failure code instead of poisoning sibling segments —
 * a dedicated type keeps the "missing params" path distinguishable from genuine launch
 * errors (LAUNCH_FAILED) in the run-segment failure accounting.
 */
public class SegmentParamsMissingException extends RuntimeException {

    public SegmentParamsMissingException(String message) {
        super(message);
    }
}
