package com.sixbean.testpike.core.mock.domain.service;

import com.sixbean.testpike.common.mq.MockRecordingFrameMessage;
import com.sixbean.testpike.core.mock.api.dto.MockPlaybackResult;
import com.sixbean.testpike.core.mock.domain.component.MockStubDraft;

import java.util.List;

/**
 * Cassette playback atomic service (AG batch , design F8 replay
 * promote half): turn what a proxy box actually recorded into save-ready stub
 * drafts and file them under the space.
 * <p>Frame source follows the box state honestly — an OPEN box reads the live
 * Redis hold (frames still arriving), a SEALED box reads the uploaded cassette
 * from object storage. An empty sealed box (no cassette) previews empty: "the
 * box recorded nothing" is a fact, never a pretend rule set.
 */
public interface MockPlaybackService {

    /**
     * Replay plan for one box without writing anything: {@code strict} = one
     * exact rule per distinct recorded URL (newest answer represents),
     * {@code fuzzy} = one template rule per normalized endpoint group (newest
     * 2xx represents). Unknown box id or unknown mode refused in plain words.
     */
    List<MockStubDraft> previewDrafts(long recordingId, String mode);

    /**
     * File the replay plan under the owning space through the SINGLE import
     * channel (normal stub save chain, MockRuleValidator stays the one
     * validation point — a draft is a proposal, never a bypass). Rules the
     * space already carries for the same method+path are skipped, so replaying
     * twice never twins a rule. The box's promoted_count accumulates the rows
     * this run created (traceability badge across repeated playbacks).
     * A box that belongs to another space is refused (spaceId is the caller's
     * declared scope, not a trust grant).
     */
    MockPlaybackResult playback(long spaceId, long recordingId, String mode);

    /**
 * Masked frame SAMPLE of one box for AI induction :
 * same box-state-honest read path as previews (live hold while open,
 * durable cassette after seal), trimmed to the newest N frames where N
 * comes from the K8 key {@code mock.ai.summarize.max-sample-frames}
 * (default 50) so an induction prompt never swallows a whole cassette.
 * An empty box samples empty; a ghost box id is refused in plain words.
 */
    List<MockRecordingFrameMessage> sampleFrames(long recordingId);
}
