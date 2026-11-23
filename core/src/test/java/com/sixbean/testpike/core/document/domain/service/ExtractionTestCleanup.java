package com.sixbean.testpike.core.document.domain.service;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * Shared row-cleanup single source for extraction-domain SpringBootTest
 * fixtures (R40: the delete-by-id cascade was about to exist in three test
 * classes). Deletion ORDER is part of the contract — ledger and requirements
 * reference the document row, chunks reference it too, and the project must
 * only go after its membership rows. Keeps R37 posture: never TRUNCATE a
 * shipped table, always delete by the ids the fixture created.
 */
final class ExtractionTestCleanup {

    private ExtractionTestCleanup() {
    }

    /** Remove one test document plus its ledger/requirement/chunk children. */
    static void deleteDocument(JdbcTemplate jdbc, long docId) {
        jdbc.update("DELETE FROM tp_doc_extract_ledger WHERE document_id=?", docId);
        jdbc.update("DELETE FROM tp_requirement WHERE source=2 AND source_id=?",
                "doc:" + docId);
        jdbc.update("DELETE FROM tp_document_chunk WHERE document_id=?", docId);
        jdbc.update("DELETE FROM tp_document WHERE id=?", docId);
    }

    /** Remove a test project plus its membership rows (call after documents). */
    static void deleteProject(JdbcTemplate jdbc, Long projectId) {
        if (projectId == null) {
            return;
        }
        jdbc.update("DELETE FROM tp_user_project WHERE project_id=?", projectId);
        jdbc.update("DELETE FROM tp_project WHERE id=?", projectId);
    }

    /** Batch form used by @AfterEach sweeps. */
    static void deleteDocuments(JdbcTemplate jdbc, List<Long> docIds) {
        docIds.forEach(id -> deleteDocument(jdbc, id));
    }
}
