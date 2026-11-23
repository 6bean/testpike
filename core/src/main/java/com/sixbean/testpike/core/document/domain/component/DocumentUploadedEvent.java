package com.sixbean.testpike.core.document.domain.component;

/**
 * Published after a document upload reaches the parsed-success state inside an
 * existing lineage group (re-upload case). Carries the coordinates the lineage
 * auto-analysis listener needs to pair the new version against the group's
 * newest parsed sibling without any manual reanalyze step.
 *
 * @param documentId     newly uploaded document row id
 * @param projectId      owning project (H1 isolation)
 * @param operatorId     upload operator used as the analysis creator
 * @param lineageGroupId lineage group the document joined (never null — the
 *                       publisher only fires for existing-group re-uploads)
 */
public record DocumentUploadedEvent(long documentId, long projectId, long operatorId,
                                    long lineageGroupId) {
}
