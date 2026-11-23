package com.sixbean.testpike.core.document.domain.component;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.document.domain.service.DocumentChangeAnalysisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Lineage auto-pair listener: consumes {@link DocumentUploadedEvent} fired by a
 * re-upload into an existing lineage group and runs the change analysis against
 * the group's newest parsed version, replacing the manual reanalyze selection.
 * An auto-diff failure never breaks the upload itself — the manual analysis
 * channel stays available, so a skip is logged with the human reason instead.
 */
@Component
public class DocumentLineageAutoAnalysis {

    private static final Logger log = LoggerFactory.getLogger(DocumentLineageAutoAnalysis.class);

    private final DocumentChangeAnalysisService changeAnalysisService;

    public DocumentLineageAutoAnalysis(DocumentChangeAnalysisService changeAnalysisService) {
        this.changeAnalysisService = changeAnalysisService;
    }

    @EventListener
    public void onDocumentUploaded(DocumentUploadedEvent event) {
        try {
            changeAnalysisService.analyze(event.operatorId(), event.documentId());
        } catch (ServiceException e) {
            log.info("血缘自动配对跳过 doc={} group={} reason={}",
                    event.documentId(), event.lineageGroupId(), e.getMessage());
        } catch (Exception e) {
            log.warn("血缘自动配对异常（人工变更分析通道仍可用）doc={}", event.documentId(), e);
        }
    }
}
