package com.sixbean.testpike.admin.webelement.infrastructure.remote.client;

import com.sixbean.testpike.admin.webelement.api.dto.CreateWebElementRequest;
import com.sixbean.testpike.admin.webelement.api.dto.CensusPostingBridgeResult;
import com.sixbean.testpike.admin.webelement.api.dto.HealEvidencePage;
import com.sixbean.testpike.admin.webelement.api.dto.ReferencingCasePage;
import com.sixbean.testpike.admin.webelement.api.dto.SurveyBridgeResult;
import com.sixbean.testpike.admin.webelement.api.dto.SurveyEventsPage;
import com.sixbean.testpike.admin.webelement.api.dto.WebElementAnchor;
import com.sixbean.testpike.admin.webelement.api.dto.RevisionStormRow;
import com.sixbean.testpike.admin.webelement.api.dto.WebElementHealthRow;
import com.sixbean.testpike.admin.webelement.api.dto.WebElementLedgerView;
import com.sixbean.testpike.admin.webelement.api.dto.WebElementPageGroupView;

import java.util.List;

/**
 * core web-element ledger client (WE batch S0). Read doors serve the ledger
 * page; write doors forward the revision token so the optimistic-lock refusal
 * reaches the browser with its original semantics.
 */
public interface CoreWebElementClient {

    WebElementLedgerView create(CreateWebElementRequest request);

    WebElementLedgerView get(long id);

    List<WebElementLedgerView> list(long projectId, String pageKey);

    List<WebElementPageGroupView> pages(long projectId);

    WebElementLedgerView updateAnchors(long id, List<WebElementAnchor> anchors, int expectedRevision);

    WebElementLedgerView changeState(long id, int targetState, int expectedRevision);

    /** Rename one element; the core door enforces the same-page unique name. */
    WebElementLedgerView rename(long id, String newName, int expectedRevision);

    /**
 * Dispatch one manual page survey (WE-S4 segment P4). Returns the explore
 * session handle the candidate pool will land under.
 */
    SurveyBridgeResult survey(long projectId, String startUrl);

    /**
 * File the census ledger-creation cards of one project .
 * Pending cards only — the generic draft door stays the write path.
 */
    CensusPostingBridgeResult postCensusLedgerDrafts(long projectId);

    /** File the census reference-replacement cards of one project. */
    CensusPostingBridgeResult postCensusScriptRefDrafts(long projectId);

    /** Heal evidence rows of one execution task (report badge surface). */
    HealEvidencePage healEvidenceByTask(long taskId);

    /** Recent heals of one ledger element, newest first, bounded paging. */
    HealEvidencePage healEvidenceByElement(long id, int page, int pageSize);

    /** Bounded page of cases referencing one ledger element (anchor ledger read). */
    ReferencingCasePage referencingCases(long id, int page, int pageSize);

    /** Survey tail window of one explore session (R62 segment c read door). */
    SurveyEventsPage surveyProcessEvents(long sessionId, long cursor, Integer limit);

    /**
 * Step-health reading per ledger row of the project segment (red/yellow/green
 * column source); a blank page key reads the whole project.
 */
    List<WebElementHealthRow> health(long projectId, String pageKey);

    /** Active revision storms of the project, one card per storming page. */
    List<RevisionStormRow> storms(long projectId);
}
