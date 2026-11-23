package com.sixbean.testpike.aiservice.tool.domain.component;

import java.util.List;

/**
 * Read port for mid-turn steering : whatever backs it
 * (the ledger sink wired by the dialog orchestrator) hands the running ReAct
 * loop the user supplements that arrived since the last round boundary, each
 * text returned exactly once — the cursor advance lives behind {@link #poll}
 * so the loop body never learns about seq bookkeeping. Generator pipelines
 * pass no reader at all and stay byte-for-byte on the old path.
 * Independent interface file — no inner types (code rules).
 */
public interface UserInjectReader {

    /** Injected texts pending at this round boundary, oldest first (empty = nobody steered). */
    List<String> poll();
}
