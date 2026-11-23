
import { describe, expect, it } from 'vitest'
import {
  applyMonitorFrames,
  degradeNotice,
  summarizeMonitorFrames,
  type MonitorFrame,
} from '@/utils/monitorFrames'

function frame(stepNo: number, image = 'aGk=', sensitive = false): MonitorFrame {
  return { taskId: 1, stepNo, image, sensitive }
}

describe('summarizeMonitorFrames（读面响应归并）', () => {
  it('读面 {frames,dropped,trimmed} 直出摘要', () => {
    const s = summarizeMonitorFrames({ frames: [frame(3), frame(2)], dropped: 0, trimmed: 0 })
    expect(s.frames).toHaveLength(2)
    expect(s.dropped).toBe(0)
    expect(s.trimmed).toBe(0)
  })
  it('非法入参=诚实空摘要（缺帧键/非数组/null 均不炸）', () => {
    for (const bad of [null, undefined, {}, { frames: 'x' }, { frames: [null, 1] }]) {
      const s = summarizeMonitorFrames(bad)
      expect(s.frames).toEqual([])
      expect(s.dropped).toBe(0)
      expect(s.trimmed).toBe(0)
    }
  })
  it('计数非数面值（字符串数字）归一为数值，缺键=0', () => {
    const s = summarizeMonitorFrames({ frames: [frame(1)], dropped: '2' })
    expect(s.dropped).toBe(2)
    expect(s.trimmed).toBe(0)
  })
})

describe('applyMonitorFrames（画面归并）', () => {
  it('普通帧=直接换面，latestStepNo 随帧', () => {
    const next = applyMonitorFrames(null, [frame(5), frame(4)])
    expect(next.latest?.stepNo).toBe(5)
    expect(next.latest?.image).toBe('aGk=')
    expect(next.pausedBySensitive).toBe(false)
    expect(next.latestStepNo).toBe(5)
  })
  it('敏感帧=遮罩暂停且保留上一可见帧（零二套打码）', () => {
    let next = applyMonitorFrames(null, [frame(3)])
    next = applyMonitorFrames(next, [frame(4, 'c2VjcmV0', true)])
    expect(next.pausedBySensitive).toBe(true)
    expect(next.latest?.stepNo).toBe(3)
    expect(next.latestStepNo).toBe(4)
  })
  it('敏感步结束后首帧恢复显示并解除暂停', () => {
    let next = applyMonitorFrames(null, [frame(3)])
    next = applyMonitorFrames(next, [frame(4, 'aGk=', true)])
    next = applyMonitorFrames(next, [frame(5)])
    expect(next.pausedBySensitive).toBe(false)
    expect(next.latest?.stepNo).toBe(5)
  })
  it('空帧数组=断流清面（等待帧态，暂停标记同步解除）', () => {
    let next = applyMonitorFrames(null, [frame(1)])
    next = applyMonitorFrames(next, [])
    expect(next.latest).toBeNull()
    expect(next.pausedBySensitive).toBe(false)
    expect(next.latestStepNo).toBeNull()
  })
  it('无 image 的坏帧诚实跳过（主链零影响）', () => {
    const next = applyMonitorFrames(null, [
      { taskId: 1, stepNo: 9, image: '', sensitive: false },
      frame(7),
    ])
    expect(next.latest?.stepNo).toBe(7)
  })
})

describe('degradeNotice（降级人话判据）', () => {
  it('dropped 优先于 trimmed（单帧超限>环形丢旧）', () => {
    expect(degradeNotice(2, 5)).toBe('dropped')
    expect(degradeNotice(0, 5)).toBe('trimmed')
    expect(degradeNotice(0, 0)).toBeNull()
  })
})
