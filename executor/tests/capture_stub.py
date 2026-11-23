"""Shared protocol-stub for the capture-round spectra (R40 single source).

Records the answers of both capture doors (metadata report + commit values) so
each spectrum asserts on wiring without repeating the stub skeleton. No network
— the transport itself is proven by the dead-endpoint spectra (no mocks).
"""
from __future__ import annotations


class CaptureRoundStubClient:
    """Protocol stand-in recording claims, metadata reports and commit answers."""

    def __init__(self, session):
        self._session = session
        self.claims = 0
        self.reports: list[dict] = []
        self.commits: list[dict] = []

    def claim_capture_session(self):
        self.claims += 1
        session, self._session = self._session, None
        return session

    def report_capture_candidates(self, session_id, *, candidates, failure_reason=None):
        self.reports.append({"sessionId": session_id, "candidates": candidates,
                             "failureReason": failure_reason})
        return len(candidates)

    def commit_capture_values(self, session_id, *, entries, failure_reason=None):
        self.commits.append({"sessionId": session_id, "entries": entries,
                             "failureReason": failure_reason})
        return len(entries)
