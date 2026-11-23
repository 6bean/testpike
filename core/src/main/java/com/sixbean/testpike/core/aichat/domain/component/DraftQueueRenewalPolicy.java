package com.sixbean.testpike.core.aichat.domain.component;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Triages the same-target draft queue right after one pending card of that
 * target was confirmed and written (AM batch piece 3: pending cards of one
 * target renew automatically — the human never clicks a manual "renew").
 * <p>Input is the full pending queue of the target in issue order, each
 * card's stored optimistic anchor, and the revision-merge gate verdict that
 * compares the card's proposal against the version that just landed:
 * <ul>
 * <li>REBASE with a moved anchor -> re-anchor onto the current version
 * (the proposal stays pending and will replay on top of the fresh row);</li>
 * <li>CONFLICT or BASELINE_MISSING (a verdict never supplied on purpose)
 * <li>anchor already on the current version -> untouched, zero writes.</li>
 * </ul>
 * The stale state is exclusive per target: if more than one card turns stale,
 * only the newest by issue order keeps an actionable stale card and the
 * older ones are auto-voided with a reason that names the survivor (retiring
 * a proposal card is a lifecycle move, not a business write — the human
 * confirmation door itself is never bypassed by this policy).
 * <p>Stateless: it only plans; the caller applies the plan inside its own
 * transaction next to the confirmation write.
 */
@Component
public class DraftQueueRenewalPolicy {

    /**
     * @param pendingIdsByIssueOrder pending draft ids of one target, oldest
     *                               issue first (the queue order)
     * @param baseVersions           draft id -> the version the proposal was cut against
     * @param mergeVerdicts          draft id -> gate verdict for replaying that
     *                               proposal onto the current version
     * @param currentVersion         the target row's version after the just-confirmed card landed
     */
    public RenewalPlan renewQueue(List<Long> pendingIdsByIssueOrder,
                                  Map<Long, Integer> baseVersions,
                                  Map<Long, RevisionMergeStatus> mergeVerdicts,
                                  int currentVersion) {
        Map<Long, Integer> renewAnchors = new HashMap<>();
        List<Long> staleCandidates = new ArrayList<>();
        for (Long draftId : pendingIdsByIssueOrder) {
            RevisionMergeStatus verdict = mergeVerdicts.get(draftId);
            if (verdict == null) {
                // A queue entry without a verdict is a wiring gap, not a pass:
                // refuse conservatively, same posture as a missing baseline.
                verdict = RevisionMergeStatus.BASELINE_MISSING;
            }
            if (verdict == RevisionMergeStatus.REBASE) {
                Integer base = baseVersions.get(draftId);
                if (base == null || base != currentVersion) {
                    renewAnchors.put(draftId, currentVersion);
                }
            } else {
                staleCandidates.add(draftId);
            }
        }
        List<Long> staleIds = new ArrayList<>();
        Map<Long, String> voided = new HashMap<>();
        if (staleCandidates.size() > 1) {
            Long survivor = staleCandidates.get(staleCandidates.size() - 1);
            staleIds.add(survivor);
            for (Long older : staleCandidates.subList(0, staleCandidates.size() - 1)) {
                voided.put(older, "同一目标上存在更新的陈旧待处理卡（卡 #" + survivor
                        + "），本卡已自动作废；如需该改动，请在更新卡上一键重新生成。");
            }
        } else {
            staleIds.addAll(staleCandidates);
        }
        return new RenewalPlan(renewAnchors, staleIds, voided);
    }
}
