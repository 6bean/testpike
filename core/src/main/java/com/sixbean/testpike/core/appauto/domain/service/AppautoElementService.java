package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.core.appauto.api.dto.AppautoElementInput;
import com.sixbean.testpike.core.appauto.api.dto.AppautoElementPageView;
import com.sixbean.testpike.core.appauto.api.dto.AppautoElementView;

/**
 * Image element library service (tp_appauto_element archive surface: the four
 * locator states IMAGE/COORD/REGION/OCR with resolution variants and a
 * confidence floor).
 * <p>Scope boundary: this service owns archive CRUD + shape validation only.
 * Runtime element resolution candidate assembly stays on the executor side
 * (driver/element_candidates.py single source); duplicating any locator
 * semantics in Java is forbidden.
 */
public interface AppautoElementService {

    /** Allowed locator states (mirrors ck_appauto_element_type on the table). */
    String TYPE_IMAGE = "IMAGE";
    String TYPE_COORD = "COORD";
    String TYPE_REGION = "REGION";
    String TYPE_OCR = "OCR";

    /**
     * Shipped default confidence floor, single-sourced from the column default
     * {@code 0.80} on tp_appauto_element.confidence_threshold (db/schema-init.sql).
     * Capture use cases fall back to this value when the caller sends none;
     * validation of any supplied value still happens in one place.
     */
    double DEFAULT_CONFIDENCE_FLOOR = 0.80d;

    /** Create one element profile; duplicate (project, name) = conflict. */
    long create(long projectId, AppautoElementInput input);

    /** Full update of one element profile (missing row = human 404). */
    AppautoElementView update(long elementId, AppautoElementInput input);

    /** Delete one element profile (missing row = human 404). */
    void delete(long elementId);

    /** Single row read (missing row = human 404). */
    AppautoElementView find(long elementId);

    /**
     * Paginated listing newest-first with optional case-insensitive name
     * keyword and locator-type filter; pageSize is clamped server-side.
     */
    AppautoElementPageView page(long projectId, String keyword, String locatorType,
                                int page, int pageSize);
}
