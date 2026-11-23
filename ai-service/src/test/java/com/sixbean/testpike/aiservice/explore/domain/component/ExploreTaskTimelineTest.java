package com.sixbean.testpike.aiservice.explore.domain.component;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * stepsTimeline detail walk (WE-S3 shared component): the first
 * non-blank detail of the wanted action wins, every other posture — absent
 * timeline, malformed JSON, non-array payload, blank details, missing
 * action — degrades to null, which consumers must read as "no evidence".
 */
class ExploreTaskTimelineTest {

    @Test
    void returnsDetailOfMatchingActionAndSkipsOtherRows() {
        String timeline = "[{\"action\":\"page-snapshot\",\"detail\":\"snap\"},"
                + "{\"action\":\"element-candidates\",\"detail\":\"{\\\"candidates\\\":[]}\"}]";
        assertEquals("{\"candidates\":[]}",
                ExploreTaskTimeline.detailFor(timeline, "element-candidates"));
    }

    @Test
    void firstNonBlankDetailWins() {
        String timeline = "[{\"action\":\"element-candidates\",\"detail\":\"  \"},"
                + "{\"action\":\"element-candidates\",\"detail\":\"pool-2\"}]";
        assertEquals("pool-2",
                ExploreTaskTimeline.detailFor(timeline, "element-candidates"));
    }

    @Test
    void malformedAbsentOrUnmatchedDegradesToNull() {
        assertNull(ExploreTaskTimeline.detailFor(null, "element-candidates"));
        assertNull(ExploreTaskTimeline.detailFor("not json", "element-candidates"));
        assertNull(ExploreTaskTimeline.detailFor("{\"k\":1}", "element-candidates"));
        assertNull(ExploreTaskTimeline.detailFor("[{\"action\":\"assert\"}]",
                "element-candidates"));
        assertNull(ExploreTaskTimeline.detailFor("[{\"action\":\"assert\",\"detail\":\"x\"}]",
                " "));
    }
}
