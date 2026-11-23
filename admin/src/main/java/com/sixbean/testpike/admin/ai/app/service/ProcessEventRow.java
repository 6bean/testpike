package com.sixbean.testpike.admin.ai.app.service;

/**
 * One forwarded process event of the shared tail-f pump — the minimal frame
 * shape every long-running AI job drawer maps its ledger rows into before the
 * pump writes them through the SSE frame writer. {@code cursor} is the ledger
 * row id (strictly increasing, the viewer's replay anchor); {@code level} is
 * one of the closed filter values (thinking / step / error); {@code detail}
 * is the machine-readable JSON payload; {@code landedAt} is the ledger's ISO
 * landing time (nullable) — carried so a reconnect replay keeps the TRUE row
 * times instead of the arrival clock (user report 2026-11-20: every replayed
 * tail row showed the same timestamp because SSE frames dropped this field).
 * The 4-arg form (no landing time) stays for hosts whose door view has no
 * timestamp yet, so they ride the legacy frame shape byte-for-byte.
 */
public record ProcessEventRow(
        long cursor,
        String level,
        String summary,
        String detail,
        String landedAt) {

    public ProcessEventRow(long cursor, String level, String summary, String detail) {
        this(cursor, level, summary, detail, null);
    }
}
