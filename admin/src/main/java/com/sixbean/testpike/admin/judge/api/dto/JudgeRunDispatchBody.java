package com.sixbean.testpike.admin.judge.api.dto;

/**
 * Dispatch-an-evaluation-run request (AW4; optional fields only — the run id
 * rides the path). evaluatedProvider names the model under evaluation; when
 * omitted core resolves it from the run's own target anchor. The four gates
 * (pending state / judge model available / sensitive-export ban / same-vendor
 * ban) and the per-sample enqueue all stay single-source in core
 * JudgeDispatchService — this bridge forwards with zero second validation.
 *
 * @param evaluatedProvider optional provider hint of the target under test
 */
public record JudgeRunDispatchBody(String evaluatedProvider) {
}
