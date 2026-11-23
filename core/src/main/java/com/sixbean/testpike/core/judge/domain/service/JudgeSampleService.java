package com.sixbean.testpike.core.judge.domain.service;

import com.sixbean.testpike.core.judge.api.dto.EvalSetVersionView;
import com.sixbean.testpike.core.judge.api.dto.SampleInput;
import com.sixbean.testpike.core.judge.api.dto.SampleView;

import java.util.List;


public interface JudgeSampleService {

    
    EvalSetVersionView publish(String setName, List<SampleInput> samples, Long createdBy);

    
    List<SampleView> listSamples(String setName, int version);

    /**
 * All published set versions, newest first ( segment A read door for the
 * run-creation picker — name x version x digest x sample-count enumerated
 * straight from the immutable version segments; zero judgement, zero rows
 * when nothing exists yet).
 */
    List<EvalSetVersionView> listSetVersions();

    
    EvalSetVersionView raiseFromTrainingSamples(String setName, List<Long> sampleIds, Long createdBy);

    
    EvalSetVersionView raiseFromBatchResults(String setName, List<Long> taskIds, Long createdBy);

    /**
 * Golden-pack harvest : freeze hand-picked adopted
 * excellent AI cases into a new immutable eval-set version — the pack the
 * drift replay pins generation-template changes against. Eligibility
 * triple (AI source + excellent mark + review approved) and the
 * requirement-anchor rule refuse the whole request naming every offending
 * row in plain language (fail-closed: no half-baked version lands).
 */
    EvalSetVersionView raiseFromAdoptedCases(String setName, List<Long> caseIds, Long createdBy);
}
