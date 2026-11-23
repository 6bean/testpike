package com.sixbean.testpike.core.behavior.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.behavior.api.dto.GoldenComparisonInput;
import com.sixbean.testpike.core.behavior.api.dto.GoldenDriftPageView;
import com.sixbean.testpike.core.behavior.api.dto.GoldenDriftVerdict;
import com.sixbean.testpike.core.behavior.domain.component.GoldenDriftGate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal surface of the golden-set drift gate .
 * The read endpoint exposes the newest replay verdict of a candidate template
 * version (the promotion refusal on the D4 endpoint quotes the same row); the
 * record endpoint lets the replay pipeline land a verdict after it has pushed
 * the domain's golden pack through both versions on one model. Recording an
 * evaluation result is data, not a template status change — the human-only
 * promotion path stays untouched (write gate red line conserved).
 */
@RestController
@RequestMapping("/internal/behavior/golden-drift")
public class GoldenDriftInternalController {

    private final GoldenDriftGate gate;

    public GoldenDriftInternalController(GoldenDriftGate gate) {
        this.gate = gate;
    }

    /** Newest verdict for one candidate version (NULL data = never replayed). */
    @GetMapping("/latest")
    public Response<GoldenDriftVerdict> latest(@RequestParam String domain,
                                               @RequestParam long candidateVersionId) {
        return Response.builderSuccess(gate.latestVerdict(domain, candidateVersionId));
    }

    /**
 * Paged route-axis ledger : every model-route replay
 * newest-first; all three filters optional so the dashboard can pull one
 * family, one switch side, or the whole ledger through the same read.
 */
    @GetMapping("/route-verdicts")
    public Response<GoldenDriftPageView> routeVerdicts(
            @RequestParam(value = "domain", required = false) String domain,
            @RequestParam(value = "controlRef", required = false) String controlRef,
            @RequestParam(value = "candidateRef", required = false) String candidateRef,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "50") int pageSize) {
        return Response.builderSuccess(gate.routeVerdictsPage(domain, controlRef, candidateRef, page, pageSize));
    }

    /** Records one golden replay comparison; the drift rate is re-derived here, never trusted from the caller. */
    @PostMapping("/verdict")
    public Response<Boolean> record(@RequestBody GoldenComparisonInput input) {
        // Non-template axes carry no template rows — absent ids read as 0, the
        // gate validates the shape per axis and never trusts the caller's stats.
        boolean landed = gate.recordComparison(input.templateDomain(), input.templateName(),
                input.comparisonKind(), input.controlRef(), input.candidateRef(),
                input.candidateVersionId() == null ? 0L : input.candidateVersionId(),
                input.controlVersionId() == null ? 0L : input.controlVersionId(),
                input.packName(), input.packVersion(), input.snapshotDigest(), input.pairs(), input.note());
        return Response.builderSuccess(landed);
    }
}
