package com.sixbean.testpike.aiservice.aichat.domain.component;

import com.sixbean.testpike.aiservice.llm.pipeline.DomainExperienceLine;

import java.util.List;

/**
 * One source page for the domain-experience section :
 * the fetched head of rows plus the TRUE total the source holds for this
 * project bucket. The total rides the same query (window count), so the
 * disclosed "more rows not injected" figure never understates reality and
 * never costs a second round trip.
 *
 * @param lines fetched experience rows (at most the per-source cap)
 * @param total total rows the source matched before paging
 */
record DomainExperienceSourcePage(List<DomainExperienceLine> lines, int total) {

    static final DomainExperienceSourcePage EMPTY =
            new DomainExperienceSourcePage(List.of(), 0);
}
