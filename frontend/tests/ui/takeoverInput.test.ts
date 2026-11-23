
import { describe, expect, it } from 'vitest'
import {
  KEYCODE_MAP,
  buildKeyCommand,
  buildSwipeCommand,
  buildTapCommand,
  buildTextCommand,
  takeoverPlatformGates,
} from '@/utils/takeoverInput'

const rect = { left: 100, top: 50, width: 800, height: 400 }
const dev = { platform: 'ANDROID', serial: 'emulator-5554' }

describe('buildTapCommand（点按归一）', () => {
  it('画面内坐标 0..1 归一 ×10000 取整并附 platform/serial', () => {
    const r = buildTapCommand(dev, rect, { clientX: 500, clientY: 250 })
    expect(r).toEqual({ platform: 'ANDROID', serial: 'emulator-5554', verb: 'tap', x: 5000, y: 5000 })
  })
  it('左上角=0、右下角=10000（含端点，与执行器闸同界）', () => {
    expect(buildTapCommand(dev, rect, { clientX: 100, clientY: 50 })?.x).toBe(0)
    const corner = buildTapCommand(dev, rect, { clientX: 900, clientY: 450 })
    expect(corner?.x).toBe(10000)
    expect(corner?.y).toBe(10000)
  })
  it('画面外点击=拒（越界禁盲注，返回 null）', () => {
    expect(buildTapCommand(dev, rect, { clientX: 950, clientY: 250 })).toBeNull()
  })
  it('零尺寸画面（帧未到/遮罩占位）=拒不炸', () => {
    expect(buildTapCommand(dev, { left: 0, top: 0, width: 0, height: 0 }, { clientX: 5, clientY: 5 })).toBeNull()
  })
})

describe('buildSwipeCommand（滑动归一）', () => {
  it('起止两点各自归一，四坐标全带', () => {
    const r = buildSwipeCommand(dev, rect, { clientX: 100, clientY: 450 }, { clientX: 900, clientY: 50 })
    expect(r).toEqual({ platform: 'ANDROID', serial: 'emulator-5554', verb: 'swipe', x1: 0, y1: 10000, x2: 10000, y2: 0 })
  })
  it('任一端越界=整条拒（禁半边盲注）', () => {
    expect(buildSwipeCommand(dev, rect, { clientX: 100, clientY: 250 }, { clientX: 9999, clientY: 250 })).toBeNull()
  })
})

describe('buildKeyCommand（按键归一）', () => {
  it('映射表内键 → keycode（Android 键码表：Enter=66/Back=4）', () => {
    expect(buildKeyCommand(dev, 'Enter')).toEqual({ platform: 'ANDROID', serial: 'emulator-5554', verb: 'key', keycode: 66 })
    expect(buildKeyCommand(dev, 'Backspace')).toMatchObject({ keycode: 67 })
  })
  it('映射表外键（如 F5）=拒不发（禁猜键码）', () => {
    expect(buildKeyCommand(dev, 'F5')).toBeNull()
  })
  it('映射表值域闸=0..255（与执行器 keycode 闸同界）', () => {
    for (const code of Object.values(KEYCODE_MAP)) expect(code).toBeGreaterThanOrEqual(0)
    for (const code of Object.values(KEYCODE_MAP)) expect(code).toBeLessThanOrEqual(255)
  })
})

describe('buildTextCommand（文本归一）', () => {
  it('正常文本 → text 指令原样带', () => {
    expect(buildTextCommand(dev, 'hello world')).toEqual({ platform: 'ANDROID', serial: 'emulator-5554', verb: 'text', text: 'hello world' })
  })
  it('空串/超 128 字符=拒（与执行器长度闸同界）', () => {
    expect(buildTextCommand(dev, '')).toBeNull()
    expect(buildTextCommand(dev, 'x'.repeat(129))).toBeNull()
    expect(buildTextCommand(dev, 'x'.repeat(128))).not.toBeNull()
  })
})

describe('takeoverPlatformGates（平台注入面置灰判据）', () => {
  it('ANDROID=四枚全开、无置灰', () => {
    const g = takeoverPlatformGates('ANDROID')
    expect(g.verbs).toEqual(['tap', 'swipe', 'key', 'text'])
    expect(g.grayReasonKey).toBeNull()
  })
  it('HARMONY_NEXT=仅 tap/swipe，key/text 诚实不支持点名（拒因 key 供人话呈现）', () => {
    const g = takeoverPlatformGates('HARMONY_NEXT')
    expect(g.verbs).toEqual(['tap', 'swipe'])
    expect(g.grayReasonKey).toBe('appauto.takeoverGrayHarmonyNoKeyText')
  })
  it('MP/IOS/QUICKAPP=无注入面整区置灰（人话拒因 key）', () => {
    for (const p of ['MP', 'IOS', 'QUICKAPP']) {
      const g = takeoverPlatformGates(p)
      expect(g.verbs).toEqual([])
      expect(g.grayReasonKey).toBe('appauto.takeoverGrayNoInjection')
    }
  })
  it('未登记平台（null/空）=诚实置灰禁猜载体', () => {
    expect(takeoverPlatformGates(null).verbs).toEqual([])
    expect(takeoverPlatformGates('').grayReasonKey).toBe('appauto.takeoverGrayPlatformUnknown')
  })
  it('指令装配闸：HARMONY_NEXT 发 text/key 在装配面即拒（前端零发送，禁打到队列）', () => {
    const hm = { platform: 'HARMONY_NEXT', serial: 'HDC-1' }
    expect(buildTextCommand(hm, 'abc')).toBeNull()
    expect(buildKeyCommand(hm, 'Enter')).toBeNull()
    expect(buildTapCommand(hm, rect, { clientX: 100, clientY: 50 })).not.toBeNull()
  })
})
