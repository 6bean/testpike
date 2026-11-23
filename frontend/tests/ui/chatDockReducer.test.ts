/**
 * stage 2b — dock frame reducer contract (pure logic, node vitest).
 *
 * The dock folds the live SSE frames into one assistant turn. The nine frame
 * types of the wire protocol map to exactly these behaviors (the "never leave
 * the user waiting" adjudication, client side):
 * - delta grows the answer text, never the process list;
 * - state/reason/trace/draft/cite append to the analysis-process area in
 * arrival order, answer text untouched;
 * - done seals the turn (never running again) and keeps usage numbers;
 * - error shows the server's human-readable sentence verbatim — no second
 * phrasing invented here (W6: the backend already names the reason);
 * - an unknown event is ignored quietly (forward compatibility, no crash).
 */
import { describe, expect, it } from 'vitest'
import {
  applyFrameToTurn,
  createAssistantTurn,
  type DockTurn,
} from '@/composables/chatDockReducer'

function frame(event: string, data: string, id: number | null = 1) {
  return { event, id, data }
}

describe('dock frame reducer', () => {
  it('delta frames grow the answer text only', () => {
    const turn = createAssistantTurn(1)
    applyFrameToTurn(turn, frame('delta', '你好'))
    applyFrameToTurn(turn, frame('delta', '，世界'))
    expect(turn.content).toBe('你好，世界')
    expect(turn.process).toHaveLength(0)
  })

  it('state/reason/trace append to the process area in arrival order', () => {
    const turn = createAssistantTurn(2)
    applyFrameToTurn(turn, frame('state', '已收到，正在路由模型'))
    applyFrameToTurn(turn, frame('reason', '分析中'))
    applyFrameToTurn(turn, frame('trace', '正在调用工具 knowledge_search'))
    expect(turn.process.map((p) => p.kind)).toEqual(['state', 'reason', 'trace'])
    expect(turn.process[2].text).toBe('正在调用工具 knowledge_search')
    expect(turn.content).toBe('')
  })

  it('draft frames land in the process area with their kind kept', () => {
    const turn = createAssistantTurn(3)
    applyFrameToTurn(turn, frame('draft', '{"action":"create"}'))
    expect(turn.process.map((p) => p.kind)).toEqual(['draft'])
  })

  // stage 3c: a draft frame whose meta side-channel carries the landed
  // draft row (draftId pinned by the landing projection, commit 24bcbba4)
  // folds into a confirmable repair card — the card needs the machine fields
  // (case anchor, base version, proposed action JSON) to render the diff and
  // drive the two-click confirm/apply flow.
  it('draft frame with landed meta folds into a repair card, process untouched', () => {
    const turn = createAssistantTurn(33)
    applyFrameToTurn(turn, {
      event: 'draft',
      id: 1,
      data: '等待动作超时，建议把 waitTimeout 从 5000 调整为 15000',
      meta: '{"draftId":77,"executionId":12,"caseId":9,"baseVersion":4,'
        + '"schemaVersion":"v2","model":"glm-4.5","actionJson":"{\\"steps\\":[]}"}',
    })
    expect(turn.repairs).toHaveLength(1)
    expect(turn.repairs[0].draftId).toBe(77)
    expect(turn.repairs[0].caseId).toBe(9)
    expect(turn.repairs[0].baseVersion).toBe(4)
    expect(turn.repairs[0].schemaVersion).toBe('v2')
    expect(turn.repairs[0].actionJson).toBe('{"steps":[]}')
    expect(turn.repairs[0].summary).toBe('等待动作超时，建议把 waitTimeout 从 5000 调整为 15000')
    expect(turn.process).toHaveLength(0)
  })

  // stage 3: the ledger is generic — a CASE_WRITE draft frame carries an
  // objectType that routes the card's presentation (create has no case
  // anchor yet, edit is cut against baseVersion). The card keeps the kind so
  // one component distributes per objectType instead of a second card clone.
  it('CASE_WRITE edit meta folds with its kind and tool name kept', () => {
    const turn = createAssistantTurn(36)
    applyFrameToTurn(turn, {
      event: 'draft',
      id: 1,
      data: '建议补充断言步骤',
      meta: '{"objectType":"CASE_WRITE","caseId":9,"baseVersion":4,'
        + '"toolName":"case.update","actionJson":"{\\"steps\\":[]}",'
        + '"schemaVersion":"","model":"","draftId":81}',
    })
    expect(turn.repairs).toHaveLength(1)
    expect(turn.repairs[0].objectType).toBe('CASE_WRITE')
    expect(turn.repairs[0].actionKind).toBe('edit')
    expect(turn.repairs[0].toolName).toBe('case.update')
    expect(turn.repairs[0].draftId).toBe(81)
  })

  it('CASE_WRITE create meta (caseId 0, no anchors) folds as an actionable create card', () => {
    const turn = createAssistantTurn(37)
    applyFrameToTurn(turn, {
      event: 'draft',
      id: 1,
      data: '新建登录成功用例',
      meta: '{"objectType":"CASE_WRITE","caseId":0,"baseVersion":0,'
        + '"toolName":"case.create","actionJson":"{\\"title\\":\\"\\"}","draftId":82}',
    })
    expect(turn.repairs).toHaveLength(1)
    expect(turn.repairs[0].actionKind).toBe('create')
    expect(turn.repairs[0].caseId).toBe(0)
  })

  it('repair meta without an objectType keeps the script-repair kind (old server drift)', () => {
    const turn = createAssistantTurn(38)
    applyFrameToTurn(turn, {
      event: 'draft',
      id: 1,
      data: '修复建议',
      meta: '{"draftId":77,"executionId":12,"caseId":9,"baseVersion":4,'
        + '"schemaVersion":"v2","model":"m","actionJson":"{}"}',
    })
    expect(turn.repairs[0].objectType).toBe('SCRIPT_REPAIR')
    expect(turn.repairs[0].actionKind).toBe('edit')
    expect(turn.repairs[0].toolName).toBe('')
  })

  // stage 5: the self-evolution proposal generator lands its cards with
  // {objectType: EVOLUTION_PROPOSAL, targetId, actionJson} — no case anchor
  // (the proposal targets a prompt family / tool row / document, not a case).
  // The fold keeps the kind and projects the payload's subKind + target name
  // onto the card so one component renders all three families; a card without
  // a landed draftId still degrades exactly like every other kind (below).
  it('EVOLUTION_PROPOSAL meta without case anchors folds into a visible proposal card', () => {
    const turn = createAssistantTurn(50)
    applyFrameToTurn(turn, {
      event: 'draft',
      id: 1,
      data: '提示词模板修订提案（观察窗口 2026-09）：追问率 12.0%',
      meta: JSON.stringify({
        objectType: 'EVOLUTION_PROPOSAL',
        targetId: 0,
        draftId: 90,
        actionJson: JSON.stringify({
          subKind: 'PROMPT_TPL', name: '在线对话回答模板', domain: 'aichat', content: '正文',
        }),
      }),
    })
    expect(turn.repairs).toHaveLength(1)
    expect(turn.repairs[0].objectType).toBe('EVOLUTION_PROPOSAL')
    expect(turn.repairs[0].subKind).toBe('PROMPT_TPL')
    expect(turn.repairs[0].targetName).toBe('在线对话回答模板')
    expect(turn.repairs[0].draftId).toBe(90)
    expect(turn.process).toHaveLength(0)
  })

  it('EVOLUTION_PROPOSAL grounding card names its document via the payload filename', () => {
    const turn = createAssistantTurn(51)
    applyFrameToTurn(turn, {
      event: 'draft',
      id: 1,
      data: '接地文档修订提案（观察窗口 2026-09）',
      meta: JSON.stringify({
        objectType: 'EVOLUTION_PROPOSAL',
        targetId: 0,
        draftId: 91,
        actionJson: JSON.stringify({ subKind: 'GROUNDING', filename: 'evolution-grounding-2026-09.md', content: '# doc' }),
      }),
    })
    expect(turn.repairs[0].subKind).toBe('GROUNDING')
    expect(turn.repairs[0].targetName).toBe('evolution-grounding-2026-09.md')
  })

  it('unknown objectType keeps the script-repair anchor requirement (whitelist drift pin)', () => {
    const turn = createAssistantTurn(52)
    applyFrameToTurn(turn, {
      event: 'draft',
      id: 1,
      data: '未来类型无锚草案不得现形',
      meta: '{"objectType":"SOMETHING_NEW","draftId":92,"actionJson":"{}"}',
    })
    // Resolved to SCRIPT_REPAIR, which is an anchored edit — no anchors means
    // no card (the confirmation door would have nothing to aim the diff at).
    expect(turn.repairs).toHaveLength(0)
    expect(turn.process).toEqual([{ kind: 'draft', text: '未来类型无锚草案不得现形' }])
  })

  // Skipped landing (draft never persisted server-side) carries no draftId in
  // the frame meta: there is no confirmation door to aim at, so the card
  // degrades honestly to the human sentence in the process area.
  it('draft frame without a landed draftId degrades to the process line', () => {
    const turn = createAssistantTurn(34)
    applyFrameToTurn(turn, {
      event: 'draft',
      id: 1,
      data: '草案未能登记，无法确认应用',
      meta: '{"executionId":12,"caseId":9,"baseVersion":4,"schemaVersion":"v2","model":"m","actionJson":"{}"}',
    })
    expect(turn.repairs).toHaveLength(0)
    expect(turn.process).toEqual([{ kind: 'draft', text: '草案未能登记，无法确认应用' }])
  })

  it('draft frame without any meta keeps the human summary in the process area', () => {
    const turn = createAssistantTurn(35)
    applyFrameToTurn(turn, { event: 'draft', id: 1, data: '旧版服务：无 meta 侧信道' })
    expect(turn.repairs).toHaveLength(0)
    expect(turn.process.map((p) => p.kind)).toEqual(['draft'])
  })

  // knife 3: cite frames fold into clickable source cards (tail zone),
  // not the analysis-process list — the adjudicated presentation renders a
  // card under the answer body (AO design doc, cite-frame section).
  it('cite frame with machine meta folds into a citation card', () => {
    const turn = createAssistantTurn(31)
    applyFrameToTurn(turn, {
      event: 'cite',
      id: 1,
      data: '[C1] 升级中心指南',
      meta: '{"index":1,"kind":"help","refId":88,"domainCode":"F","title":"升级中心指南","excerpt":"升级前…","score":0.9}',
    })
    expect(turn.citations).toHaveLength(1)
    expect(turn.citations[0]).toMatchObject({
      index: 1, kind: 'help', refId: 88, domainCode: 'F', title: '升级中心指南',
    })
    // The citation never duplicates into the process area.
    expect(turn.process).toHaveLength(0)
  })

  it('cite frame without parsable meta degrades honestly to the process line', () => {
    const turn = createAssistantTurn(32)
    applyFrameToTurn(turn, frame('cite', '[C2] 旧客户端无 meta 通道'))
    expect(turn.citations).toHaveLength(0)
    expect(turn.process).toEqual([{ kind: 'cite', text: '[C2] 旧客户端无 meta 通道' }])
  })

  it('done seals the turn and keeps the usage numbers from its payload', () => {
    const turn = createAssistantTurn(4)
    applyFrameToTurn(turn, frame('delta', '答案'))
    applyFrameToTurn(turn, frame('done', '{"messageId":99,"promptTokens":10,"completionTokens":5}'))
    expect(turn.state).toBe('sealed')
    expect(turn.messageId).toBe(99)
    expect(turn.usage).toEqual({ promptTokens: 10, completionTokens: 5 })
  })

  it('error keeps the server sentence verbatim and seals as failed', () => {
    const turn = createAssistantTurn(5)
    applyFrameToTurn(turn, frame('delta', '半截正文'))
    applyFrameToTurn(turn, frame('error', '在线 AI 对话当前未开启（系统设置中「在线 AI 对话」开关打开后重新提问即可）。'))
    expect(turn.state).toBe('failed')
    expect(turn.content).toBe('半截正文')
    expect(turn.errorText).toBe('在线 AI 对话当前未开启（系统设置中「在线 AI 对话」开关打开后重新提问即可）。')
  })

  it('an unknown event is ignored without touching the turn', () => {
    const turn: DockTurn = createAssistantTurn(6)
    applyFrameToTurn(turn, frame('mystery-protocol-extension', 'x'))
    expect(turn.content).toBe('')
    expect(turn.process).toHaveLength(0)
    expect(turn.state).toBe('running')
  })

  // stage 3b: the trace frame of the case.flow-guide tool journals its
  // structured answer onto meta.guide (ai-service commit da7cafe5). The reducer
  // folds it into turn.guide — the current status' actions become the dialog's
  // action buttons. The human trace line still lands in the process area (the
  // guide is a side channel, never a replacement of the trajectory line).
  it('trace frame with guide meta folds the current-status actions into turn.guide', () => {
    const turn = createAssistantTurn(40)
    applyFrameToTurn(turn, {
      event: 'trace',
      id: 1,
      data: '工具 case.flow-guide 执行完成',
      meta: JSON.stringify({
        phase: 'tool_done',
        tool: 'case.flow-guide',
        success: true,
        guide: {
          caseId: 77,
          caseTitle: '登录冒烟',
          currentStatus: 2,
          currentStatusLabel: '待评审',
          states: [
            { code: 1, label: '草稿', terminal: false, actions: [] },
            {
              code: 2,
              label: '待评审',
              terminal: false,
              actions: [
                { event: 'pass', eventLabel: '评审通过', toStatus: 3, toStatusLabel: '已通过', page: '/review', queryKey: 'caseId' },
                { event: 'need_change', eventLabel: '打回修改', toStatus: 4, toStatusLabel: '需修改', page: '/review', queryKey: 'caseId' },
              ],
            },
          ],
        },
      }),
    })
    expect(turn.process).toEqual([{ kind: 'trace', text: '工具 case.flow-guide 执行完成' }])
    expect(turn.guide).not.toBeNull()
    expect(turn.guide!.caseId).toBe(77)
    expect(turn.guide!.caseTitle).toBe('登录冒烟')
    expect(turn.guide!.currentStatusLabel).toBe('待评审')
    expect(turn.guide!.actions).toHaveLength(2)
    expect(turn.guide!.actions[0]).toMatchObject({ event: 'pass', eventLabel: '评审通过', page: '/review', queryKey: 'caseId' })
  })

  it('guide fold keeps only actions whose page exists in the menu leaves', () => {
    const turn = createAssistantTurn(41)
    applyFrameToTurn(turn, {
      event: 'trace',
      id: 1,
      data: '工具 case.flow-guide 执行完成',
      meta: JSON.stringify({
        guide: {
          caseId: 78,
          currentStatus: 2,
          currentStatusLabel: '待评审',
          states: [
            {
              code: 2,
              label: '待评审',
              terminal: false,
              actions: [
                { event: 'pass', eventLabel: '评审通过', toStatus: 3, toStatusLabel: '已通过', page: '/review', queryKey: 'caseId' },
                { event: 'ghost', eventLabel: '幻影动作', toStatus: 9, toStatusLabel: '未知', page: '/no-such-page', queryKey: 'caseId' },
              ],
            },
          ],
        },
      }),
    })
    expect(turn.guide!.actions.map((a) => a.event)).toEqual(['pass'])
  })

  it('guide fold unwraps the Response envelope dialed over the HTTP tool link', () => {
    // The flow-guide tool is dialed as exec_kind=1 against the core internal
    // door; its journaled output is that door's raw envelope
    // {"code":"0","msg":null,"data":{...FlowGuideInfo}} (core
    // ToolExecutionServiceImpl.executeHttp returns the body verbatim), so the
    // fold must unwrap `data` before reading the graph.
    const turn = createAssistantTurn(44)
    applyFrameToTurn(turn, {
      event: 'trace',
      id: 1,
      data: '工具 case.flow-guide 执行完成',
      meta: JSON.stringify({
        phase: 'tool_done',
        tool: 'case.flow-guide',
        success: true,
        guide: {
          code: '0',
          msg: null,
          data: {
            caseId: 79,
            caseTitle: '下单链路',
            currentStatus: 2,
            currentStatusLabel: '待评审',
            states: [
              {
                code: 2,
                label: '待评审',
                terminal: false,
                actions: [
                  { event: 'pass', eventLabel: '评审通过', toStatus: 3, toStatusLabel: '已通过', page: '/review', queryKey: 'caseId' },
                ],
              },
            ],
          },
        },
      }),
    })
    expect(turn.guide!.caseId).toBe(79)
    expect(turn.guide!.actions).toHaveLength(1)
  })

  it('trace frame without guide meta (or with unusable shape) leaves turn.guide null', () => {
    const turn = createAssistantTurn(42)
    applyFrameToTurn(turn, frame('trace', '正在调用工具 case.flow-guide'))
    applyFrameToTurn(turn, {
      event: 'trace',
      id: 2,
      data: '工具 other 执行完成',
      meta: '{"phase":"tool_done","tool":"other","success":true}',
    })
    applyFrameToTurn(turn, { event: 'trace', id: 3, data: '坏负载', meta: '{"guide":"not-an-object"}' })
    expect(turn.guide).toBeNull()
    expect(turn.process).toHaveLength(3)
  })

  // (user complaint 2026-10-30): the model streams its thinking as
  // dozens-to-hundreds of tiny reason frames. One <li> per frame rendered a
  // fragment waterfall. Adjudicated display semantics = fold consecutive
  // reason frames into the LAST process entry when it is a reason entry
  // (typewriter growth, same posture as answer deltas), and open a new entry
  // only at the model's own paragraph boundaries (a frame that carries a
  // newline). Discrete kinds (state/trace/draft/cite) keep one entry per
  // frame — those lines are "one step, one event". The wire protocol, the
  // ledger frame granularity and the replay semantics are untouched: this is
  // display aggregation at the one folding point.
  it('consecutive reason fragments fold into one paragraph entry', () => {
    const turn = createAssistantTurn(50)
    applyFrameToTurn(turn, frame('reason', '先看'))
    applyFrameToTurn(turn, frame('reason', '失败清单，'))
    applyFrameToTurn(turn, frame('reason', '再定位断言'))
    expect(turn.process).toHaveLength(1)
    expect(turn.process[0].kind).toBe('reason')
    expect(turn.process[0].text).toBe('先看失败清单，再定位断言')
  })

  it('a reason frame holding a paragraph break splits into two entries', () => {
    const turn = createAssistantTurn(51)
    applyFrameToTurn(turn, frame('reason', '第一段'))
    applyFrameToTurn(turn, frame('reason', '\n\n第二段'))
    expect(turn.process).toHaveLength(2)
    expect(turn.process.map((p) => p.text)).toEqual(['第一段', '第二段'])
    expect(turn.process.every((p) => p.kind === 'reason')).toBe(true)
  })

  it('a trailing newline closes the paragraph so the next fragment opens a new entry', () => {
    const turn = createAssistantTurn(52)
    applyFrameToTurn(turn, frame('reason', '第一段\n'))
    applyFrameToTurn(turn, frame('reason', '第二段'))
    expect(turn.process.map((p) => p.text)).toEqual(['第一段', '第二段'])
  })

  it('mixed process stream keeps discrete frames one-per-entry around the folded reason paragraph', () => {
    const turn = createAssistantTurn(53)
    applyFrameToTurn(turn, frame('state', '已收到，正在路由模型'))
    applyFrameToTurn(turn, frame('reason', '分析'))
    applyFrameToTurn(turn, frame('reason', '上下文'))
    applyFrameToTurn(turn, frame('trace', '正在调用工具 knowledge_search'))
    applyFrameToTurn(turn, frame('reason', '整理'))
    applyFrameToTurn(turn, frame('reason', '结论'))
    expect(turn.process.map((p) => p.kind)).toEqual(['state', 'reason', 'trace', 'reason'])
    expect(turn.process[1].text).toBe('分析上下文')
    expect(turn.process[3].text).toBe('整理结论')
  })

  it('consecutive discrete frames never fold into one entry', () => {
    const turn = createAssistantTurn(54)
    applyFrameToTurn(turn, frame('trace', '正在调用工具 a'))
    applyFrameToTurn(turn, frame('trace', '工具 a 执行完成'))
    applyFrameToTurn(turn, frame('state', '步骤 2'))
    expect(turn.process).toHaveLength(3)
  })

  it('guide without a live case (vocabulary map) folds with empty actions', () => {
    const turn = createAssistantTurn(43)
    applyFrameToTurn(turn, {
      event: 'trace',
      id: 1,
      data: '工具 case.flow-guide 执行完成',
      meta: JSON.stringify({
        guide: { caseId: null, caseTitle: null, currentStatus: null, currentStatusLabel: null, states: [] },
      }),
    })
    expect(turn.guide).not.toBeNull()
    expect(turn.guide!.caseId).toBeNull()
    expect(turn.guide!.actions).toHaveLength(0)
  })
})
