


export interface TakeoverCommand {
  platform: string
  serial: string
  verb: 'tap' | 'swipe' | 'key' | 'text'
  x?: number
  y?: number
  x1?: number
  y1?: number
  x2?: number
  y2?: number
  keycode?: number
  text?: string
}


export interface StageRect {
  left: number
  top: number
  width: number
  height: number
}


export interface TakeoverDeviceCtx {
  platform?: string | null
  serial: string
}

const COORD_MAX = 10000
const KEYCODE_MIN = 0
const KEYCODE_MAX = 255
const TEXT_MIN_LEN = 1
const TEXT_MAX_LEN = 128


export const KEYCODE_MAP: Readonly<Record<string, number>> = Object.freeze({
  Backspace: 67, // KEYCODE_DEL
  Enter: 66, // KEYCODE_ENTER
  Tab: 61, // KEYCODE_TAB
  Escape: 111, // KEYCODE_ESCAPE
  ArrowUp: 19, // KEYCODE_DPAD_UP
  ArrowDown: 20, // KEYCODE_DPAD_DOWN
  ArrowLeft: 21, // KEYCODE_DPAD_LEFT
  ArrowRight: 22, // KEYCODE_DPAD_RIGHT
  Home: 3, // KEYCODE_HOME
  Back: 4,
})


const NO_INJECTION_PLATFORMS = new Set(['MP', 'IOS', 'QUICKAPP'])


export interface PlatformGates {
  
  verbs: string[]
  
  grayReasonKey: string | null
}


export function takeoverPlatformGates(platform?: string | null): PlatformGates {
  if (platform === 'ANDROID') return { verbs: ['tap', 'swipe', 'key', 'text'], grayReasonKey: null }
  if (platform === 'HARMONY_NEXT') {
    return { verbs: ['tap', 'swipe'], grayReasonKey: 'appauto.takeoverGrayHarmonyNoKeyText' }
  }
  if (platform && NO_INJECTION_PLATFORMS.has(platform)) {
    return { verbs: [], grayReasonKey: 'appauto.takeoverGrayNoInjection' }
  }
  return { verbs: [], grayReasonKey: 'appauto.takeoverGrayPlatformUnknown' }
}

function canInject(dev: TakeoverDeviceCtx, verb: string): boolean {
  return takeoverPlatformGates(dev.platform).verbs.includes(verb)
}


function normCoord(px: number, origin: number, size: number): number | null {
  if (!Number.isFinite(size) || size <= 0) return null
  if (!Number.isFinite(px) || !Number.isFinite(origin)) return null
  const ratio = (px - origin) / size
  if (ratio < 0 || ratio > 1) return null
  return Math.round(ratio * COORD_MAX)
}


export function buildTapCommand(dev: TakeoverDeviceCtx, rect: StageRect,
  point: { clientX: number; clientY: number }): TakeoverCommand | null {
  if (!canInject(dev, 'tap')) return null
  const x = normCoord(point.clientX, rect.left, rect.width)
  const y = normCoord(point.clientY, rect.top, rect.height)
  if (x === null || y === null) return null
  return { platform: dev.platform ?? '', serial: dev.serial, verb: 'tap', x, y }
}


export function buildSwipeCommand(dev: TakeoverDeviceCtx, rect: StageRect,
  down: { clientX: number; clientY: number }, up: { clientX: number; clientY: number }): TakeoverCommand | null {
  if (!canInject(dev, 'swipe')) return null
  const x1 = normCoord(down.clientX, rect.left, rect.width)
  const y1 = normCoord(down.clientY, rect.top, rect.height)
  const x2 = normCoord(up.clientX, rect.left, rect.width)
  const y2 = normCoord(up.clientY, rect.top, rect.height)
  if (x1 === null || y1 === null || x2 === null || y2 === null) return null
  return { platform: dev.platform ?? '', serial: dev.serial, verb: 'swipe', x1, y1, x2, y2 }
}


export function buildKeyCommand(dev: TakeoverDeviceCtx, key: string): TakeoverCommand | null {
  if (!canInject(dev, 'key')) return null
  const keycode = KEYCODE_MAP[key]
  if (keycode === undefined || keycode < KEYCODE_MIN || keycode > KEYCODE_MAX) return null
  return { platform: dev.platform ?? '', serial: dev.serial, verb: 'key', keycode }
}


export function buildTextCommand(dev: TakeoverDeviceCtx, text: string): TakeoverCommand | null {
  if (!canInject(dev, 'text')) return null
  if (typeof text !== 'string' || text.length < TEXT_MIN_LEN || text.length > TEXT_MAX_LEN) return null
  return { platform: dev.platform ?? '', serial: dev.serial, verb: 'text', text }
}
