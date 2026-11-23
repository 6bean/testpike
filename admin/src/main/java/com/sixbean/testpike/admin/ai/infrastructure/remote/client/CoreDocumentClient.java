package com.sixbean.testpike.admin.ai.infrastructure.remote.client;

import com.sixbean.testpike.admin.ai.api.dto.ChunkContextView;
import com.sixbean.testpike.admin.ai.api.dto.ConfirmExtractionRequest;
import com.sixbean.testpike.admin.ai.api.dto.ConfirmExtractionResultView;
import com.sixbean.testpike.admin.ai.api.dto.DocumentCategoryRequest;
import com.sixbean.testpike.admin.ai.api.dto.DocumentChunkView;
import com.sixbean.testpike.admin.ai.api.dto.DocumentInfoView;
import com.sixbean.testpike.admin.ai.api.dto.DocumentUsageSwitchesRequest;
import com.sixbean.testpike.admin.ai.api.dto.ExtractRequest;
import com.sixbean.testpike.admin.ai.api.dto.ExtractionEventView;
import com.sixbean.testpike.admin.ai.api.dto.ExtractionPreviewView;
import com.sixbean.testpike.admin.project.api.dto.PageView;

import java.util.List;


public interface CoreDocumentClient {

 
    DocumentInfoView upload(long projectId, byte[] file, String fileName, String category);

    
    default DocumentInfoView upload(long projectId, byte[] file, String fileName) {
        return upload(projectId, file, fileName, null);
    }

    /** Document list inside one project: status filter + name keyword +
     *  exact category filter + paging. The optional sort pair forwards to the
     *  core column whitelist (single gate). */
    PageView<DocumentInfoView> list(long projectId, Integer status, String keyword, String category,
                                    int page, int size, String sortBy, String sortDir);

    
    DocumentInfoView get(long id);

    
    void delete(long id);

    /** In-place category edit (field-level PATCH under the row lock; stale token=40901). */
    DocumentInfoView updateCategory(long id, DocumentCategoryRequest request);

    /** Usage-switch group edit (all three values required, same row lock). */
    DocumentInfoView updateUsageSwitches(long id, DocumentUsageSwitchesRequest request);

    
    DocumentInfoView extract(long id, ExtractRequest request);

    /** Explicit user cancel of a running extraction (core rotates the generation token and
 * settles the row back to parsed with the cancel reason; "nothing running" = 409 honest
 * rejection, self-recovery lane). */
    DocumentInfoView cancelExtraction(long id);

    
    ExtractionPreviewView extraction(long id, int page, int size);

    /** Cursor window of the run's process events , read-only
 * observation face; limit=null defers to the core hot key). */
    List<ExtractionEventView> extractionEvents(long id, long cursor, Integer limit);

    /** segment C1: cursor window of the newest channel-B spec-extraction
 * attempt (ledger-resolved run anchor, fail-soft empty window). */
    List<ExtractionEventView> specExtractionEvents(long id, long cursor, Integer limit);

    /** All source chunks of one document in order (AP-4 evidence-anchor read
     *  surface; core already enforces project-agnostic row shape here, the
     *  permission gate sits on the admin controller). */
    List<DocumentChunkView> chunks(long id);

    /**
 * WR batch segment C: context door of one source chunk (requested chunk
 * verbatim + preceding neighbours + position marker). A foreign or stale
 * chunk id comes back as core's honest refusal — the admin bridge passes it
 * through and never re-probes ownership via the full chunk list.
 */
    ChunkContextView chunkContext(long id, long chunkId);

    
    ConfirmExtractionResultView confirmExtraction(long id, ConfirmExtractionRequest request);

    /** Full-store confirm : core
 * enumerates the whole preview itself, so a paged viewer can never cap
 * what lands. No body; idempotent replay skips every anchored item. */
    ConfirmExtractionResultView confirmAllExtraction(long id);
}
