package com.sixbean.testpike.aiservice.doc.domain.component;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * tracks the extraction generations this process is executing, so a
 * graceful shutdown can write each one back as an honest failure instead of leaving
 * the row stuck in "extracting" under a dead lease.
 */
@Component
public class DocExtractInFlightRegistry {

    private final Map<String, DocExtractInFlightEntry> entries = new ConcurrentHashMap<>();

    /** Admits one generation; a same-key re-register replaces (single writer per generation). */
    public void register(DocExtractInFlightEntry entry) {
        entries.put(entry.key(), entry);
    }

    /** Releases one generation on any terminal exit (success, failure, requeue). */
    public void unregister(long documentId, String extractToken) {
        entries.remove(documentId + ":" + extractToken);
    }

    /** Read-only view used by tests and operators; never a write path. */
    public List<DocExtractInFlightEntry> snapshot() {
        return List.copyOf(entries.values());
    }

    /** Takes ownership of every registered generation and clears the map atomically
     *  per key, so a shutdown write-back can never double-fire on a repeated stop. */
    public List<DocExtractInFlightEntry> drain() {
        List<DocExtractInFlightEntry> taken = new ArrayList<>();
        Iterator<Map.Entry<String, DocExtractInFlightEntry>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            taken.add(it.next().getValue());
            it.remove();
        }
        return taken;
    }
}
