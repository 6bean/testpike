package com.sixbean.testpike.aiservice.doc.domain.component;

/**
 * segment B — raised inside the extraction pipeline when the generation
 * self-check reads a foreign extract token from core: a preempt/newer submit
 * already owns the document, so this run aborts itself.
 * <p>The outer pipeline treats it as an honest stop (audit warn + quiet
 * return, so the MQ consumer acks), NEVER as a failure — failExtraction
 * would roll back the newer generation's status. Extends RuntimeException
 * (not ServiceException) so the ServiceException failure catch can never
 * fold it into the failure write-back path.
 */
public class ExtractGenerationSupersededException extends RuntimeException {

    public ExtractGenerationSupersededException(long documentId, String staleToken, String liveToken) {
        super("提取轮次已被新一代接管，本代中止 documentId=" + documentId
                + " staleToken=" + staleToken + " liveToken=" + liveToken);
    }
}
