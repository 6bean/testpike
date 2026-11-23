package com.sixbean.testpike.admin.cases.app.component;

import com.sixbean.testpike.admin.auth.infrastructure.remote.client.ConfigValueClient;
import com.sixbean.testpike.admin.cases.api.dto.SimilarityHintLevel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


class SimilarityHintGraderTest {

    private final ConfigValueClient configValueClient = mock(ConfigValueClient.class);
    private final SimilarityHintGrader grader = new SimilarityHintGrader(configValueClient);

    private void stubDefaults() {
        when(configValueClient.getValue(SimilarityHintGrader.KEY_HINT, "0.80")).thenReturn("0.80");
        when(configValueClient.getValue(SimilarityHintGrader.KEY_STRONG, "0.90")).thenReturn("0.90");
    }

 
    @Test
    void itKb03FourPointsGradeExactly() {
        stubDefaults();

        assertEquals(SimilarityHintLevel.NONE, grader.grade(0.79));
        assertEquals(SimilarityHintLevel.HINT, grader.grade(0.80));
        assertEquals(SimilarityHintLevel.HINT, grader.grade(0.89));
        assertEquals(SimilarityHintLevel.STRONG, grader.grade(0.90));
    }

 
    @Test
    void dirtyThresholdFallsBackToFrozenDefaults() {
        when(configValueClient.getValue(SimilarityHintGrader.KEY_HINT, "0.80")).thenReturn("abc");
        when(configValueClient.getValue(SimilarityHintGrader.KEY_STRONG, "0.90")).thenReturn("");

        assertEquals(SimilarityHintLevel.NONE, grader.grade(0.79));
        assertEquals(SimilarityHintLevel.HINT, grader.grade(0.80));
        assertEquals(SimilarityHintLevel.STRONG, grader.grade(0.95));
    }

    
    @Test
    void invertedThresholdFallsBack() {
        when(configValueClient.getValue(SimilarityHintGrader.KEY_HINT, "0.80")).thenReturn("0.95");
        when(configValueClient.getValue(SimilarityHintGrader.KEY_STRONG, "0.90")).thenReturn("0.85");

        assertEquals(SimilarityHintLevel.STRONG, grader.grade(0.90));
    }

    
    @Test
    void thresholdsReReadPerGrade() {
        when(configValueClient.getValue(SimilarityHintGrader.KEY_HINT, "0.80")).thenReturn("0.70");
        when(configValueClient.getValue(SimilarityHintGrader.KEY_STRONG, "0.90")).thenReturn("0.95");

        assertEquals(SimilarityHintLevel.HINT, grader.grade(0.75));
    }
}
