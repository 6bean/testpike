package com.sixbean.testpike.core.document.domain.service;

import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.document.api.dto.DocumentInfo;

import java.util.List;
import java.util.Map;


public interface DocumentService {

    
    DocumentInfo upload(long operatorId, long projectId, String filename, byte[] content,
                        String category);

 
    DocumentInfo get(long id);

    
    PageResult<DocumentInfo> list(long projectId, Integer status, String keyword, int page, int size);

    /**
 * Document list with server-side sorting : the sort pair is
 * resolved against the core column whitelist in SQL, so the whole project
 * window orders instead of the browser reshuffling one pageful. A null/blank
 * key keeps the historical id DESC default.
 *
 * @param sortBy whitelist key (id/name/file_type/size_bytes/chunk_count/
 * doc_category/status/file_hash); an off-list value is
 * refused with a human 400 naming the whitelist
 * @param sortDir asc for ascending, anything else (incl. null) = desc
 * @param category optional doc_category exact filter (document screening):
 * served server-side over the whole project window; a
 * blank/null value keeps all categories
 */
    PageResult<DocumentInfo> list(long projectId, Integer status, String keyword, String category,
                                  int page, int size, String sortBy, String sortDir);

 
    void delete(long operatorId, long id);

    /**
 * Field-level category edit under the row optimistic lock ( package 2,
 * same posture as the plugin/tool edit doors): the caller must hand back
 * the revision it read; a stale token is refused with 40901 naming the
 * current version, a missing token with 40001. Category changes never
 * rewrite the usage switches as a side effect, and no downstream ingest
 * is re-triggered (DDL_DICT bridge stays upload-only per the ruling).
 *
 * @param operatorId audit operator
 * @param id document id (404 with a readable message when missing)
 * @param category target category (required; off-list values get a 400 naming the set)
 * @param revision expected row version (null=40001 -- the lock token is mandatory,
 * silent force-overwrite is refused)
 * @return the edited view (revision already bumped)
 */
    DocumentInfo updateCategory(long operatorId, long id, String category, Integer revision);

    /**
     * Per-document usage switches edit: all three switches must
     * be sent with their intended values (a missing field is refused with a
     * readable 40001 naming it -- no silent half-write), consumed under the
     * same optimistic lock as {@link #updateCategory}.
     *
     * @param operatorId       audit operator
     * @param id               document id (404 with a readable message when missing)
     * @param useForExtraction extraction participation (null=40001)
     * @param useInCasePrompt  case-prompt participation (null=40001)
     * @param useInGrounding   grounding participation (null=40001)
     * @param revision         expected row version (null=40001)
     * @return the edited view (revision already bumped)
     */
    DocumentInfo updateUsageSwitches(long operatorId, long id, Boolean useForExtraction,
                                     Boolean useInCasePrompt, Boolean useInGrounding,
                                     Integer revision);

    /**
 * Chunk text list (ai-service extraction input -- AiDocExtractMessage carries
 * only four fields without text, so chunks are pulled via the internal endpoint).
 */
    List<Map<String, Object>> chunks(long id);

    /**
 * Jump-to-source context door : the requested chunk
 * plus up to three preceding neighbours of the same document and the
 * chunk's 1-based position marker, so the extraction preview can explain a
 * thin chunk with its surrounding original text. Neighbour text obeys the
 * hot-updatable budget key {@code ai.doc.chunk-context-chars} — over
 * budget the farthest neighbours drop first. A foreign or stale chunk id
 * rejects honestly with zero writes.
 */
    com.sixbean.testpike.core.document.api.dto.ChunkContext chunkContext(
            long documentId, long chunkId);
}
