package com.sixbean.testpike.core.aichat.domain.component;

import java.util.Optional;

/**
 * Draft proposal kinds the generic dialog-draft ledger accepts (AO batch
 * stage 1).
 * <p>Single source of the {@code tp_ai_chat_draft.object_type} value set: the
 * landing side refuses frames naming an unregistered kind (no strategy = no
 * honest way to write it later, so nothing may become confirmable), and the
 * confirmation chain dispatches the write to the matching strategy by the same
 * string. Adding a new AI write proposal kind = one enum value here plus one
 * write-strategy bean — the confirmation door itself never gets a second copy.
 */
public enum DraftObjectType {

    /** Repair proposal for a case script (landed via ScriptService.saveRepaired). */
    SCRIPT_REPAIR("SCRIPT_REPAIR"),

    /**
 * Create-or-edit proposal for a test case (AO batch stage 2, landed
 * via CaseService). target_id NULL = create (the case id is pinned back
 * onto the ledger row when the write lands); target_id set + base_version
 * set = field-level edit cut against that case version.
 */
    CASE_WRITE("CASE_WRITE"),

    /**
 * Create/rename/merge proposal for a tag-dictionary row (tool-surface
 * clearing batch stage 2, landed via TagService). target_id NULL =
 * create; target_id set = rename of that tag; a merge proposal pins the
 * from-tag into target_id and keeps the to-tag inside the payload.
 */
    TAG_WRITE("TAG_WRITE"),

    /**
 * New-version proposal for a prompt template (tool-surface clearing batch
 * stage 3a, landed via PromptTemplateService.save). The template
 * family is versioned append-only — "edit = new version row" — so the
 * only shape the emitter produces is a create: target_id starts NULL and
 * the new row id is pinned back onto the ledger when the write lands.
 */
    PROMPT_TPL_WRITE("PROMPT_TPL_WRITE"),

    /**
 * New-skill proposal for the skill library (tool-surface clearing batch
 * stage 3b, landed via SkillService.create). Skills are project
 * assets with a human approval chain, so the only shape the emitter
 * produces is a create that stays in draft state: target_id starts NULL
 * and the new row id is pinned back onto the ledger when the write lands.
 */
    SKILL_WRITE("SKILL_WRITE"),

    /**
 * Review-decision proposal for a test case (tool-surface clearing batch
 * stage 3c, landed via CaseService.review). A decision always names
 * an existing case, so target_id is the case id and base_version the
 * optimistic anchor the door re-checks; confirming the card moves the
 * case through the SAME review state machine the workbench uses.
 */
    CASE_REVIEW("CASE_REVIEW"),

    /**
 * New-plan proposal for the execution plan domain (tool-surface clearing
 * batch stage 4a, landed via PlanService.create). A plan create is
 * always a create — the scope-rule case pull happens inside the same
 * chain the wizard uses — so target_id starts NULL and the new row id is
 * pinned back onto the ledger when the write lands; the row stays in
 * draft state (publishing stays a deliberate human click).
 */
    PLAN_WRITE("PLAN_WRITE"),

    /**
 * New-batch proposal for the execution batch domain (tool-surface clearing
 * batch stage 4b, landed via BatchService.create). A draft batch from
 * the dialog carries the checked case set exactly like the batches-page
 * form — case ownership, end-environment matching and audit are enforced
 * by that same chain — so target_id starts NULL and the new row id is
 * pinned back onto the ledger when the write lands; starting the batch
 * (which snapshots and queues the tasks) stays a deliberate human click.
 */
    BATCH_WRITE("BATCH_WRITE"),

    /**
 * New-test-report proposal for the summary-report domain (tool-surface
 * clearing batch stage 4c, landed via TestReportService.generate).
 * A report generate is always a create — the aggregation snapshot is
 * computed and inserted by the SAME chain the report page's generate
 * dialog uses — so target_id starts NULL and the new report id is pinned
 * back onto the ledger when the write lands. Exporting and deleting
 * reports stay deliberate human clicks on /test-report.
 */
    REPORT_WRITE("REPORT_WRITE"),

    /**
 * Collection-defaults save proposal for the api-test domain (tool-surface
 * clearing batch stage 5a, landed via ApiCollectionService.rename —
 * the full save face the collection dialog uses). Saving defaults always
 * acts on an existing collection, so target_id is the collection id and
 * the door re-checks that it belongs to the session project; the applied
 * row has no separate version for this kind (the collection is updated in
 * place, mirroring the report kind's null-applied-version shape).
 */
    APITEST_WRITE("APITEST_WRITE"),

    /**
 * Contract-pull proposal for the api-inventory domain (tool-surface
 * clearing batch stage 5b, landed via ApiSpecService.fetchFromUrl —
 * the SAME URL-entry chain the inventory page's pull dialog uses, so the
 * SSRF whitelist, the size/format gates, the SHA256 idempotency and the
 * version-chain semantics stay single-sourced). A pull is always an
 * append onto the project's version chain: the owning project comes from
 * the chat session (never the payload), target_id starts NULL and the new
 * spec row id is pinned back onto the ledger when the write lands, with
 * spec_version as the applied version.
 */
    APISPEC_WRITE("APISPEC_WRITE"),

    /**
 * Pressure-run launch proposal for the perf domain (tool-surface
 * clearing batch stage 5c, tenth cleared domain, landed via
 * {@code PerfRunService.launch} — the SAME launch transaction the
 * workbench's launch dialog uses, so the production-change safety gate,
 * the duration ceiling, the canary pre-flight and the environment
 * execution lock stay single-sourced and the dialog is never a bypass).
 * <p>Launching is the heaviest write face the dialog can propose: the
 * draft card carries a cost statement (which environment gets occupied
 * and that a rejection surfaces with its reason), and confirmation runs
 * the full gate chain again inside the launch transaction — an emitter-
 * side check could only ever be a notice, never the decision.
 * <p>Attribution guard (H1): the owning project comes from the chat
 * session, never from the payload; the scenario id is re-checked to
 * belong to that project by the launch chain itself. A launch creates a
 * run row: target_id is pinned to the scenario and the produced run id
 * has no version ledger for this kind (applied version = null).
 */
    PERF_LAUNCH("PERF_LAUNCH"),

    /**
 * Device-reservation create proposal for the app-automation domain
 * (tool-surface clearing batch stage 6a, landed via
 * {@code AppautoReservationService.create} — the SAME create chain the
 * reservation calendar's dialog uses, so the time-window validation, the
 * device-existence check, the conflict refusal (it names the current
 * holder and the expected release time) and the approval-switch routing
 * stay single-sourced and the dialog is never a bypass).
 * <p>Attribution guard (H1): the owning project comes from the chat
 * session, never from the payload; the confirming human becomes the
 * reservation holder (conflict wording needs a named person). A created
 * reservation row has no version ledger for this kind (applied version =
 * null), while target_id keeps the device anchor the card was emitted
 * with — the object the human confirmed an action ON. Releasing or
 * cancelling an existing reservation stays a deliberate human click on
 * /appauto/reservations.
 */
    RESERVATION_WRITE("RESERVATION_WRITE"),

    /**
 * Popup-rule self-learning proposal for the app-automation domain
 * (tool-surface clearing batch stage 6b, landed via
 * {@code AppautoPopupRuleService.learn(taskId)} — the SAME learn chain
 * the approval view's "learn" button uses, so the learning switch
 * (ai.appauto.popup-rule-learn, off = refused in plain words with zero
 * model calls), the sensitive-frame exclusion, the structure gate
 * (fail-closed) and the pending-approval status all re-decide at
 * landing time and the dialog is never a bypass).
 * <p>Attribution guard (H1): the task's owning project is read from
 * tp_exec_task by the learn chain; this kind compares it against the
 * chat session's project BEFORE calling and refuses a foreign task in
 * plain words — the payload can never steer another project's lane.
 * A learned rule row starts as a pending draft (status = 1): confirming
 * the card only produces the draft, while approving it into the rule
 * library stays a deliberate human click on /appauto/popup-rules. The
 * draft row has no version ledger for this kind (applied version =
 * null); target_id is rewritten to the produced rule id, the object the
 * human confirmed an action ON.
 */
    POPUP_RULE_LEARN("POPUP_RULE_LEARN"),

    /**
 * Mock-space create proposal for the interface-simulation domain
 * (tool-surface clearing batch stage 7, thirteenth cleared domain,
 * landed via {@code MockSpaceService.create} — the SAME save-chain
 * create the space page's dialog uses, so the structural validator
 * (name length, routing-key shape, mode/auth/miss-policy one-of sets,
 * rate/quota/chaos bounds), the project existence check, the
 * production-environment ban (env_type=4 refused) and the site-wide
 * routing-key uniqueness conflict all re-decide at landing time and
 * the dialog is never a bypass).
 * <p>Attribution guard (H1): the owning project is read from the chat
 * session, never from the payload — the input record carries no project
 * field at all, so a model-forged call cannot open a space in another
 * project's lane. A created space is always a DRAFT row (status = 0):
 * confirming the card only produces the draft, while publishing it
 * (the transition that opens the traffic door under /mocks/) stays a
 * deliberate human click on /mock/spaces. The draft row has no version
 * ledger for this kind (applied version = null); target_id starts 0
 * (no space row exists when the card is emitted) and is rewritten to
 * the produced space id — the object the human then publishes or edits.
 */
    MOCK_SPACE_WRITE("MOCK_SPACE_WRITE"),

    /**
 * Mock stub rule proposal for the interface-simulation endpoint domain
 * (tool-surface clearing batch stage 8, fourteenth cleared domain,
 * landed via {@code MockStubService.create/update} — the SAME save-chain
 * the endpoint page's editor uses, so the rule validator (name, method
 * one-of, path shape, matcher/response JSON shape, fault-parameter gate),
 * the space-existence check, the scenario/state declaration gate and the
 * optimistic version conflict all re-decide at landing time and the
 * dialog is never a bypass.
 * <p>Attribution guard (H1): the owning space is re-read at landing and
 * its project compared against the chat session — a model-forged spaceId
 * or stubId can never file or edit a rule in another project's lane.
 * <p>A new rule row serves traffic only while its owning space is
 * published and the rule is enabled: the space publishing and the
 * enabled switch stay deliberate human decisions, the card states the
 * serving gate up front. The edit proposal carries the version the model
 * saw (base_version); a stale echo refuses the landing with the
 * plain-word conflict.
 */
    MOCK_STUB_WRITE("MOCK_STUB_WRITE"),

    /**
 * AI case-generation batch submit proposal for the /ai/generate domain
 * (tool-surface clearing batch stage 9, fifteenth cleared domain,
 * landed via {@code AiBatchService.createBatch} plus the per-item MQ
 * publish the batch controller runs — the SAME enqueue chain the
 * generation page's batch dialog uses, so the requirement list legality
 * (non-empty lines, per-line length ceiling, the K8-hot
 * {@code ai.batch.max-count} batch ceiling) all re-decide at landing time
 * and the dialog is never a bypass).
 * <p>Attribution guard (H1): the owning project is read from the chat
 * session, never from the payload — the submit input carries no project
 * field, so a model-forged call cannot queue generation in another
 * project's lane.
 * <p>Confirming the card only QUEUES asynchronous generation: produced
 * cases land in the review state machine for the human review loop
 * (auto-approve never happens), and the card states that gate up front.
 * The produced batch id is pinned back onto the ledger row as target_id;
 * a batch has no version ledger for this kind (applied version = null).
 */
    AIGEN_BATCH_WRITE("AIGEN_BATCH_WRITE"),

    /**
 * Defect create/edit/transition proposal for the defect library domain
 * (tool-surface clearing batch stage 10, sixteenth cleared domain,
 * landed via {@code DefectLibraryService.create/update/transition} — the
 * SAME write chain the defect page's dialogs use, so the title ceiling,
 * the severity one-of, the same-project checks on the linked case/plan,
 * the fixer-membership refusal, the closed-state edit ban, the six-state
 * transition table and the optimistic version conflict all re-decide at
 * landing time and the dialog is never a bypass).
 * <p>Attribution guard (H1): {@code DefectCreateInput} carries a project
 * field the model COULD steer, so this strategy ignores the payload value
 * and injects the session project; edit/transition re-read the row and
 * compare its project against the session before the chain runs — a
 * forged defect id can never file or move a row in another project's
 * lane.
 * <p>Create lands the row in the initial "new" state — moving a defect
 * through processing/fixed/verifying/closed stays the human
 * collaboration the page owns, and closing or reopening without a
 * reason is refused by the chain. The
 * edit/transition proposal carries the version the model saw
 * (base_version); a stale echo refuses the landing with the plain-word
 * conflict, and the applied row version is written back so the next
 * echo anchors correctly.
 */
    DEFECT_WRITE("DEFECT_WRITE"),

    /**
 * Data-factory script create/edit proposal for the L5 data-factory
 * domain (tool-surface clearing batch stage 11, seventeenth cleared
 * domain, landed via {@code DataFactoryService.create/update} — the SAME
 * save chain the data-factory page's editor uses, so the type one-of
 * (API/SQL/script), the name/content non-blank mandates, the
 * env-belongs-to-project check and the pool-key grammar plus per
 * project+env uniqueness gate all re-decide at landing time and the
 * dialog is never a bypass).
 * <p>Attribution guard (H1): {@code DataFactoryCreateInput} carries a
 * project field the model COULD steer, so this strategy ignores the
 * payload value and files the row in the chat session's project; edit
 * re-reads the row and compares its project against the session before
 * the chain runs — a forged factory id can never touch another
 * project's scripts.
 * <p>Confirming the card only saves the script definition: running it
 * (which dials the real API/SQL/python engine and writes the data pool)
 * stays a deliberate human click on /data-factories. tp_data_factory is
 * updated in place with no version ledger (same null-applied-version
 * shape as the api-test kind); target_id NULL = create (the produced row
 * id is pinned back onto the ledger), target_id set = edit of that row.
 */
    DATA_FACTORY_WRITE("DATA_FACTORY_WRITE"),

    /**
 * Exploration session start proposal for the L9 explore domain (tool-
 * surface clearing batch stage 12, eighteenth and last debt domain
 * cleared, landed via {@code ExploreService.create} — the SAME create
 * chain the explore page's dialog runs, so the budget fuse (cost guard
 * refuses when the project budget is exhausted), the goal non-blank and
 * length mandates, the web-form SSRF floor and the mobile-form env
 * whitelist gate all re-decide at landing time and the dialog is never
 * a bypass).
 * <p>Attribution guard (H1): the payload may name a project the model
 * could steer, so this strategy never reads it — the session is filed in
 * the chat session's project.
 * <p>Unlike the read-only drafts, confirming this card STARTS work: the
 * explore kernel picks the session up and spends tokens, so the card
 * must state that cost side up front (same honesty posture as the perf
 * launch kind). tp_explore_session has no version ledger; target_id is
 * pinned back to the produced session id on landing.
 */
    EXPLORE_CREATE("EXPLORE_CREATE"),

    /**
     * Self-evolution improvement proposal (AF-i phase 2b-ii, three families:
     * prompt-template revision / new tool definition / grounding-document
     * revision). Generated only from a manually triggered observation run —
     * never by a scheduler, never auto-landed. The payload carries a
     * {@code subKind} discriminator plus the proposal body; the landing
     * strategy dispatches onto the SAME chains the human pages use
     * (PromptTemplateService.save / ToolService.create / DocumentService
     * .upload), so the version chain, the draft→review gate and the ingest
     * pipeline stay single-sourced and the evolution loop is never a bypass.
     * <p>Attribution: the run's operator owns the evolution session the card
     * lands on; the confirm door re-checks session ownership like every
     * other kind. Produced rows keep their own human gates on purpose — a
     * tool draft still needs review, a prompt card still appends a version,
     * a grounding doc still enters the ingest chain — and the applied
     * version ledger only exists for the prompt family (other kinds report
     * null, mirroring their standalone twins).
     */
    EVOLUTION_PROPOSAL("EVOLUTION_PROPOSAL"),

    /**
 * Flaky-quarantine proposal for one test case (AO batch ,
 * closed-loop operations S2: failure analysis judges a case flaky, the
 * dialog emits this card, the human door decides). Landed via
 * {@code CaseService.setFlaky} — the SAME isolation chain the workbench's
 * one-click quarantine uses, so the audit row, the idempotent reason
 * refresh (repeat isolation never stacks marks) and the version bump stay
 * single-sourced and the dialog is never a bypass.
 * <p>Attribution guard (H1): target_id is the case id the analysis
 * observed; the door re-checks that the case belongs to the chat
 * session's project and that the base_version anchor is still current —
 * a stale echo refuses with the plain-word version conflict. The
 * quarantine is reversible by the same human face (un-isolating and the
 * probe scheduling belong to later slices), and releasing a case the AI
 * marked always stays a deliberate human click.
 */
    FLAKY_ISOLATION("FLAKY_ISOLATION"),

    /**
 * Create-or-edit proposal for a requirement point (AL batch , landed
 * via RequirementService). target_id NULL = create (the requirement id is
 * pinned back onto the ledger row when the write lands); target_id set =
 * field-level edit cut against the optimistic anchor — re-read server-side
 * at landing when the V330 anchor switch is on. The dialog is a manual
 * source (3): the Jira main-source channel is deliberately not
 * implemented for this batch, and the version ledger snapshots ride the
 * SAME write chain the requirement page uses.
 */
    REQUIREMENT_WRITE("REQUIREMENT_WRITE"),

    /**
     * Version-rollback proposal for a test case (version-history dialog
     * landing side). The confirmed card replays the case onto an older
     * snapshot through the SAME CaseService.rollback chain the case page
     * uses — append-only snapshot table, optimistic version lock, script
     * restore and audit all come from the shared chain, never a second
     * rollback implementation. target_id is the case anchor, base_version
     * the optimistic lock, and the payload names only the target version.
     */
    CASE_ROLLBACK("CASE_ROLLBACK"),

    /**
 * Distilled project-memory entry awaiting human confirmation (AP batch
 * , the draft-confirmation gate re-opened by the user's
 * re-ruling). The ONLY system-origin kind:
 * the ledger row carries session_id=NULL plus a project anchor, so the
 * door's system branch (login → anchor → membership → memory key)
 * guards it instead of session ownership. Landed via
 * {@code CtxMemoryDraftStrategy} — the awaiting row (inject_status=2)
 * written at distillation time is promoted to active (1) and becomes
 * assembly-injectable; nothing else is written, so the entry keeps one
 * single injection chain (no bypass, no second path).
 */
    /**
     * Interface pass-through change proposal (AI pass-through batch P1): the model named one
     * catalog entry of this application's own endpoints plus that entry's declared parameters, and
     * confirming the card lands the change through
     * {@code com.sixbean.testpike.core.apiaccess.domain.service.impl.ApiAccessWriteDraftStrategy}.
     * <p>This kind exists because the dialog otherwise cannot reach a capability that has no
     * purpose-built tool: instead of a new write strategy per endpoint, one kind routes through the
     * pass-through door's own gates (per-entry governance registration, declared whitelist,
     * project membership, change budget). Attribution guard, same rule as every other kind: the
     * owning project is taken from the chat session and re-stamped onto the request — a payload
     * that names another project is refused, never honored. target_id starts NULL and the produced
     * row id is pinned back onto the ledger when the write lands; a change has no version ledger
     * for this kind (applied version = null).
     */
    API_ACCESS_WRITE("API_ACCESS_WRITE"),

    /**
 * Login-state registration proposal for the session domain (screenshot
 * capture batch piece D): the dialog parsed a DevTools Application
 * panel screenshot server-side (OCR, values never transit the model) and
 * offers the candidate table as a check-list card — rows the user red-marked
 * arrive pre-checked. Landed via {@code LoginStateDraftStrategy}, which
 * re-reads the freshly parsed values from the transient capture channel and
 * files the checked subset through the SAME {@code LoginStateService.register}
 * ledger chain the CDP capture wizard uses (vault encryption, D9 same-name
 * renewal, replay verification and the four-state light all stay
 * single-sourced — the dialog is never a second write path).
 * <p>Attribution guard (H1): the owning project comes from the chat
 * session, and the staging slot itself is anchored to that session, so a
 * forged token cannot drain another session's parsed credentials. Secret
 * values NEVER ride the draft payload: they live only in the TTL-bound
 * transient slot until the vault encrypts them (piece E red line).
 * A registered state row has no version ledger for this kind (applied
 * version = null); target_id is rewritten to the produced ledger row id.
 */
    LOGIN_STATE_WRITE("LOGIN_STATE_WRITE"),

    /**
     * Re-approval proposal for a production pressure-test scenario (AN
     * ticket 4b): when an approved scenario is edited again, the linkage
     * bus files one confirmable card whose acceptance files a FRESH pending
     * approval request through the SAME PerfApprovalService.apply chain the
     * approval page uses (env/concurrency/isolation validation, project
     * membership and the fail-closed scenario anchor all re-decide there —
     * the strategy is never a second approval write, R40).
     * <p>The original ticket is written history: landing never rewrites the
     * old row, it only appends a new request the approver judges on the
     * scenario's current shape. System-origin cards anchor the project on
     * the approval row's project_id column (DraftProjectAnchor), dialog
     * cards keep the session door. An approval ticket has no version ledger
     * for this kind (applied version = null); target_id starts as the
     * history ticket the card points at and is rewritten to the produced
     * fresh request id when the write lands.
     */
    PERF_APPROVAL("PERF_APPROVAL"),

    /**
 * New page-element library row proposed by the WE-S6 census migration
 * : the survey files one pending card per
 * repeated inline element; posting writes nothing. Only the human door
 * lands the row, and it lands through the SAME WebElementService.create
 * chain the ledger page uses — the survey is structurally unable to
 * self-apply. target_id starts NULL and the produced ledger row id is
 * pinned back by the landing (no version ledger for this kind).
 */
    WEB_ELEMENT_WRITE("WEB_ELEMENT_WRITE"),

    /**
 * Script reference-replacement proposal lifting one surveyed script to
 * a page-element reference version (WE-S6 census migration, piece
 * 3b, landed via ScriptService.saveRepaired under the same optimistic
 * anchor the repair kind uses). target_id is the case id and the
 * structured payload carries the reference-form action JSON plus the
 * pre-replacement copy that keeps the diff trail and makes per-case
 * rollback possible through the same door.
 */
    WEB_ELEMENT_SCRIPT_REF("WEB_ELEMENT_SCRIPT_REF"),

    CTX_MEMORY("ctx_memory");

    /** Wire/meta value carried by the draft frame (emitters upstream pin this token). */
    public final String wireName;

    DraftObjectType(String wireName) {
        this.wireName = wireName;
    }

    /** Meta without an explicit kind is the only kind upstream emitters produce. */
    public static Optional<DraftObjectType> resolveOrDefault(String raw) {
        String value = raw == null || raw.isBlank() ? SCRIPT_REPAIR.wireName : raw.trim();
        for (DraftObjectType type : values()) {
            if (type.wireName.equals(value)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
