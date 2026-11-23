package com.sixbean.testpike.core.webelement.domain.component;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * One census row (WE-S6): an element identity seen on one normalized page,
 * with the scripts that reference it inline. scriptCount is the migration
 * value rank — the more scripts repeat one hand-written locator, the more it
 * is worth moving into the page element library as a reference.
 */
public record WebElementCensusEntry(String pageKey,
                                    String anchorIdentity,
                                    LinkedHashMap<String, String> anchors,
                                    List<Long> scriptIds,
                                    int stepCount) {

    public WebElementCensusEntry {
        anchors = new LinkedHashMap<>(anchors);
        scriptIds = List.copyOf(scriptIds);
    }

    public int scriptCount() {
        return scriptIds.size();
    }
}
