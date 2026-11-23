package com.sixbean.testpike.aiservice.judge.domain.service;

import com.sixbean.testpike.aiservice.judge.api.dto.QaGenJobView;
import com.sixbean.testpike.aiservice.judge.api.dto.QaGenPreviewView;
import com.sixbean.testpike.aiservice.judge.api.dto.QaGenRequest;
import com.sixbean.testpike.aiservice.judge.api.dto.QaGenRunView;
import com.sixbean.testpike.aiservice.judge.api.dto.QaGenSubmitView;
import com.sixbean.testpike.aiservice.taskstream.domain.component.TaskProcessEvent;

import java.util.List;

/**
 * <p>Job plane : {@code submit} keeps the inline
 * preview only while the sealed group count stays inside
 * judge.qa-gen.sync-group-max; over that cap it accepts a background run and
 * the progress faces ({@code jobStatus} / {@code jobEvents}) answer from the
 * job ledger plus the shared process-event window. The legacy synchronous
 * {@code generate} face stays byte-identical for existing callers.
 */
public interface EvalQuestionGenService {

    
    QaGenPreviewView generate(QaGenRequest request);

    /**
     * Two-shape submit: {@code sync} answers the full preview inline for runs
     * inside the group cap, {@code accepted} opens a background job and hands
     * back its run token. A closed grouped-intake door honestly refuses the
     * job face naming its own switch.
     */
    QaGenSubmitView submit(QaGenRequest request);

    /** Newest job row of the document, or null when no job run ever started. */
    QaGenJobView jobStatus(long documentId);

    /** Viewer window of the run's process events after {@code cursor}, ascending. */
    List<TaskProcessEvent> jobEvents(long documentId, String runToken, long cursor, int limit);

    /**
     * Run history of the document's question-generation door, newest first
     * (revisit entry of the tail window): every past run stays replayable
     * through {@code jobEvents} while its frames survive retention. The
     * terminal verdict is resolved read-side — running for the live job row,
     * failed when the run's stream carries an error frame, done otherwise.
     */
    List<QaGenRunView> qaGenRuns(long documentId, int limit);
}
