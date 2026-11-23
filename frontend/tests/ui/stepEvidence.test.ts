
import { describe, expect, it } from 'vitest'
import {
  buildFailedStepEvidence,
  evidencePanesOf,
  normalizeEvidenceRows,
  parsePerfSampleRows,
  slicePerfSamplesByStep,
  triptychHasAny,
  type StepEvidenceRow,
} from '@/utils/stepEvidence'

function timelineItem(stepNo: number, status: string): { stepNo: number; status: string } {
  return { stepNo, status }
}

function evRow(stepNo: number, over: Partial<StepEvidenceRow> = {}): StepEvidenceRow {
  return {
    taskId: 7,
    stepNo,
    action: 'app.tap',
    screenshotKey: `s${stepNo}.png`,
    screenshotUrl: `http://s3obj/s${stepNo}?sig=1`,
    viewTreeKey: `s${stepNo}.json`,
    viewTreeUrl: `http://s3obj/t${stepNo}?sig=1`,
    logTailKey: `s${stepNo}.log`,
    logTailUrl: `http://s3obj/l${stepNo}?sig=1`,
    ...over,
  }
}

describe('normalizeEvidenceRows（读面容错）', () => {
  it('合法数组=原样保留行对象', () => {
    const rows = normalizeEvidenceRows([evRow(1), evRow(2)])
    expect(rows).toHaveLength(2)
    expect(rows[0].stepNo).toBe(1)
  })
  it('null/非数组/行非对象/stepNo 非数值=诚实剔除不炸', () => {
    expect(normalizeEvidenceRows(null)).toEqual([])
    expect(normalizeEvidenceRows('x')).toEqual([])
    const rows = normalizeEvidenceRows([evRow(1), null, 3, { stepNo: '2' }, { taskId: 1 }])
    expect(rows).toHaveLength(1)
  })
})

describe('evidencePanesOf（六槽固定序+诚实缺位）', () => {
  it('六面全在=present 全真，槽序 screenshot/viewTree/logTail/record/netTimeline/perfTimeline（T329 第六联性能采样曲线）', () => {
    const panes = evidencePanesOf(evRow(3, {
      recordKey: 'r3.mp4', recordUrl: 'http://s3obj/r3?sig=1',
      netTimelineKey: 'n3.jsonl', netTimelineUrl: 'http://s3obj/n3?sig=1',
      perfTimelineKey: 'p3.jsonl', perfTimelineUrl: 'http://s3obj/p3?sig=1',
    }))
    expect(panes.map((p) => p.slot)).toEqual(['screenshot', 'viewTree', 'logTail', 'record', 'netTimeline', 'perfTimeline'])
    expect(panes.every((p) => p.present)).toBe(true)
  })
  it('旧契约行零 record 键=第四槽诚实缺位占位（零新上传件不假造回放，存量行零漂移）', () => {
    const panes = evidencePanesOf(evRow(3))
    const rec = panes.find((p) => p.slot === 'record')
    expect(rec?.present).toBe(false)
    expect(rec?.url).toBe(null)
  })
  it('旧契约行零 netTimeline 键=第五槽诚实缺位占位（无弱网会话不假造时序，存量行零漂移）', () => {
    const panes = evidencePanesOf(evRow(3))
    expect(panes).toHaveLength(6)
    const net = panes.find((p) => p.slot === 'netTimeline')
    expect(net?.present).toBe(false)
    expect(net?.url).toBe(null)
  })
  it('旧契约行零 perfTimeline 键=第六槽诚实缺位占位（无性能会话不假造曲线，存量行零漂移）', () => {
    const panes = evidencePanesOf(evRow(3))
    const perf = panes.find((p) => p.slot === 'perfTimeline')
    expect(perf?.present).toBe(false)
    expect(perf?.url).toBe(null)
  })
  it('log_tail 缺位（key/url 双 null 值域预留）=该槽 present false，其余不受影响', () => {
    const panes = evidencePanesOf(evRow(3, { logTailKey: null, logTailUrl: null }))
    const log = panes.find((p) => p.slot === 'logTail')
    expect(log?.present).toBe(false)
    expect(log?.url).toBe(null)
    expect(panes.find((p) => p.slot === 'screenshot')?.present).toBe(true)
  })
  it('url 空串=视同缺位（预签名缺产诚实占位，禁渲染空链接）', () => {
    const panes = evidencePanesOf(evRow(1, { viewTreeUrl: '' }))
    expect(panes.find((p) => p.slot === 'viewTree')?.present).toBe(false)
  })
})

describe('parsePerfSampleRows / slicePerfSamplesByStep（T329 第六联直绘件）', () => {
  it('JSONL 多行=逐行搬运指标（执行器现算 jankPct 直取，前端零重算）', () => {
    const text = [
      '{"ts":100.0,"totalFrames":300,"jankyFrames":12,"jankPct":4.0,"cpuPct":35.5}',
      '{"ts":101.2,"pssKb":204800,"tempC":41.3}',
    ].join('\n')
    const rows = parsePerfSampleRows(text)
    expect(rows).toHaveLength(2)
    expect(rows[0].jankPct).toBe(4.0)
    expect(rows[0].pssKb).toBeUndefined()
    expect(rows[1].pssKb).toBe(204800)
  })
  it('坏行/ts 非数值/非对象=诚实跳过（执行器侧同口径，禁炸渲染链）', () => {
    const text = ['not-json', '{"ts":"x"}', '[1]', '{"ts":102.0,"cpuPct":10}', ''].join('\n')
    expect(parsePerfSampleRows(text)).toEqual([{ ts: 102.0, cpuPct: 10 }])
  })
  it('NaN/Inf 指标值=诚实剔除该键（禁假值进曲线）', () => {
    const rows = parsePerfSampleRows('{"ts":1,"jankPct":null,"cpuPct":7}')
    expect(rows[0].jankPct).toBeUndefined()
    expect(rows[0].cpuPct).toBe(7)
  })
  it('步行有双钟区间=切片取 [tsStart,tsEnd]（同钟面直取禁换算，坑③消费位）', () => {
    const samples = parsePerfSampleRows(
      '{"ts":10}\n{"ts":20}\n{"ts":30}\n{"ts":40}',
    )
    expect(slicePerfSamplesByStep(samples, { tsStart: 15, tsEnd: 35 }).map((s) => s.ts))
      .toEqual([20, 30])
  })
  it('步行无 tsStart/tsEnd（旧任务/无会话）=返回全量诚实展示不假造空', () => {
    const samples = parsePerfSampleRows('{"ts":10}\n{"ts":20}')
    expect(slicePerfSamplesByStep(samples, {})).toHaveLength(2)
  })
})

describe('buildFailedStepEvidence（失败步×证据并表）', () => {
  it('失败步配对证据行；成功步不出现', () => {
    const out = buildFailedStepEvidence(
      [timelineItem(1, 'ok'), timelineItem(2, 'fail')],
      [evRow(1), evRow(2)],
    )
    expect(out).toHaveLength(1)
    expect(out[0].stepNo).toBe(2)
    expect(out[0].action).toBe('app.tap')
    expect(triptychHasAny(out[0])).toBe(true)
  })
  it('失败步无证据行=仍出行为三面全缺诚实空态', () => {
    const out = buildFailedStepEvidence([timelineItem(4, 'fail')], [])
    expect(out).toHaveLength(1)
    expect(out[0].panes.every((p) => !p.present)).toBe(true)
    expect(triptychHasAny(out[0])).toBe(false)
  })
  it('证据行步号不在失败步清单=不混入（读面全步返回，失败面只点失败步）', () => {
    const out = buildFailedStepEvidence([timelineItem(2, 'fail')], [evRow(1), evRow(2), evRow(3)])
    expect(out.map((e) => e.stepNo)).toEqual([2])
  })
  it('时间线缺 stepNo/status 或 status 空=该步不入面（结构不完整诚实跳过）', () => {
    const out = buildFailedStepEvidence(
      [{ status: 'fail' }, { stepNo: 5 }, timelineItem(6, ''), timelineItem(7, 'fail')],
      [],
    )
    expect(out.map((e) => e.stepNo)).toEqual([7])
  })
})
