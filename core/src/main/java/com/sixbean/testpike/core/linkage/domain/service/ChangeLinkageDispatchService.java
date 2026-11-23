package com.sixbean.testpike.core.linkage.domain.service;

import com.sixbean.testpike.core.linkage.api.dto.LinkageImpactSummary;
import com.sixbean.testpike.core.linkage.api.dto.ObjectChangedEventMessage;

/**
 * Downstream detection entry of the change-linkage bus — the single consumer
 * seam every {@code {objectType}.changed} announcement flows through.
 * <p>The bus never lets a save chain scatter per-domain hooks: the edge
 * registry ({@code tp_change_linkage}) says which downstream objects a save
 * may invalidate, this service runs those detectors and writes only the
 * impact ledger ({@code tp_linkage_impact}). Marking is automatic, content is
 * not: per the batch design adjudication section 3.1, {@code mark_only} is a
 * reversible display state applied with zero clicks, {@code grouped_draft}
 * lands as a pending row that only the one-click human accept can consume,
 * and {@code disclose_only} records a disclosure without touching the issued
 * row. Detection therefore rewrites no downstream business table.
 */
public interface ChangeLinkageDispatchService {

    /**
     * Walk the active edges of one announced upstream object and persist the
     * downstream impacts. Idempotent per upstream version: re-announcing the
     * same version writes nothing new and re-arms nothing already consumed.
     *
     * @param message announcement from the event bus (or a save chain replaying
     *                one); an unknown object type is skipped with a log line
     * @return detection account of this pass
     */
    LinkageImpactSummary detectDownstream(ObjectChangedEventMessage message);
}
