package com.sixbean.testpike.core.aichat.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.aichat.domain.component.DraftObjectType;
import com.sixbean.testpike.core.aichat.domain.service.DraftWriteStrategy;
import com.sixbean.testpike.core.script.api.dto.ScriptInfo;
import com.sixbean.testpike.core.script.domain.service.ScriptService;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Script-repair landing strategy behind the generic draft door (AO batch
 * stage 1; the write half of the repair chain, moved verbatim so
 * behaviour and refusal wording spectrum stay identical for the repair card).
 * <p>Owns the kind-specific optimistic anchor: the proposal was cut against
 * tp_script.version = base_version, so the live version must still match
 * before {@code saveRepaired} lands one new version. A mismatch (40901)
 * writes nothing; the confirmation chain burns the ticket either way —
 * stale proposals need a fresh human confirmation, never a blind retry.
 */
@Service
public class RepairDraftWriteStrategy implements DraftWriteStrategy {

    private final ScriptService scriptService;

    public RepairDraftWriteStrategy(ScriptService scriptService) {
        this.scriptService = scriptService;
    }

    @Override
    public String objectType() {
        return DraftObjectType.SCRIPT_REPAIR.wireName;
    }

    @Override
    public Integer land(Map<String, Object> draftRow, long operatorId) {
        return land(draftRow, operatorId, null);
    }

    @Override
    public Integer land(Map<String, Object> draftRow, long operatorId, String selectedAction) {
        long caseId = ((Number) draftRow.get("target_id")).longValue();
        int baseVersion = ((Number) draftRow.get("base_version")).intValue();
        ScriptInfo current = scriptService.getByCaseId(caseId);
        if (current == null) {
            throw new ServiceException("该用例当前没有脚本，修复草案无法落版——请在「AI 对话」对该用例执行「生成脚本」后，再回到「草案确认卡」确认修复草案",
                    BizErrorCode.NOT_FOUND);
        }
        if (current.version() != baseVersion
                && !DraftWriteStrategy.ACTION_FORCE.equals(selectedAction)) {
            // Optimistic lock: the proposal was cut against a stale script
            // line. Refused with the gate-baseline wording — both versions
            // the refresh/re-analyse dead-end is retired); a force choice
            // replays the repaired script onto the current line.
            throw new ServiceException("脚本已被他人修改（草案基于版本 " + baseVersion
                    + "，当前版本 " + current.version() + "），本次修复未写入——"
                    + "请基于最新版本重新确认，或选择「以本次为准」让本修复方案直接落版",
                    BizErrorCode.VERSION_CONFLICT);
        }
        ScriptInfo saved = scriptService.saveRepaired(caseId,
                (String) draftRow.get("schema_version"), (String) draftRow.get("payload_json"));
        return saved.version();
    }
}
