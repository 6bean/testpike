package com.sixbean.testpike.core.document.domain.service;

import com.sixbean.testpike.core.project.domain.service.ProjectService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Shared collaborator wiring for the document-extraction test family (R40
 * single source). Every extraction test previously re-declared the identical
 * @Autowired cluster (extraction/document/project services, jdbc, redis), so
 * the wiring block existed twice in the tree. Tests now autowire this rig and
 * read the collaborators off it; the wiring lives in exactly one place.
 * <p>Picked up by the component scan of {@code CoreApplication} because the
 * rig sits inside the scanned test package of the same root — no extra config.
 */
@Component
class DocumentExtractionTestRig {

    final DocumentExtractionService extractionService;
    final DocumentService documentService;
    final ProjectService projectService;
    final JdbcTemplate jdbcTemplate;
    final StringRedisTemplate redis;

    DocumentExtractionTestRig(DocumentExtractionService extractionService,
            DocumentService documentService,
            ProjectService projectService,
            JdbcTemplate jdbcTemplate,
            StringRedisTemplate redis) {
        this.extractionService = extractionService;
        this.documentService = documentService;
        this.projectService = projectService;
        this.jdbcTemplate = jdbcTemplate;
        this.redis = redis;
    }

    /**
     * Per-test fixture slot: the project/doc rows the running test built and
     * must sweep by id afterwards (R37 baseline duty). Tracked on the rig so
     * the adopt-and-clean lifecycle exists once for the whole family instead
     * of being re-declared per test class (R40).
     */
    Long projectId;
    Long docId;

    /** Register the fixture rows built by a preparing helper for later cleanup. */
    void adoptDocs(ExtractionPreviewFixtures.Docs docs) {
        this.projectId = docs.projectId();
        this.docId = docs.docId();
    }

    /** Sweep the fixture rows this test adopted; safe when nothing was adopted. */
    void cleanDocs() {
        ExtractionPreviewFixtures.cleanDocsFixtures(jdbcTemplate, projectId, docId);
        docId = null;
        projectId = null;
    }
}
