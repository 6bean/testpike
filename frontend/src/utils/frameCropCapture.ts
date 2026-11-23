


export interface DragPoint {
  clientX: number
  clientY: number
}


export interface ImgBox {
  left: number
  top: number
  width: number
  height: number
}


export interface FrameRegion {
  x: number
  y: number
  width: number
  height: number
}


export const CROP_SCALE_PRESETS = [1, 2]


const MIN_REGION_AREA_PX = 4

function clamp(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), max)
}


export function toFrameRegion(
  from: DragPoint,
  to: DragPoint,
  box: ImgBox,
  naturalWidth: number,
  naturalHeight: number,
): FrameRegion | null {
  if (box.width <= 0 || box.height <= 0) return null
  if (naturalWidth <= 0 || naturalHeight <= 0) return null
  const scaleX = naturalWidth / box.width
  const scaleY = naturalHeight / box.height
  const fx0 = clamp((Math.min(from.clientX, to.clientX) - box.left) * scaleX, 0, naturalWidth)
  const fy0 = clamp((Math.min(from.clientY, to.clientY) - box.top) * scaleY, 0, naturalHeight)
  const fx1 = clamp((Math.max(from.clientX, to.clientX) - box.left) * scaleX, 0, naturalWidth)
  const fy1 = clamp((Math.max(from.clientY, to.clientY) - box.top) * scaleY, 0, naturalHeight)
  const x = Math.round(fx0)
  const y = Math.round(fy0)
  const width = Math.round(fx1 - fx0)
  const height = Math.round(fy1 - fy0)
  if (width * height < MIN_REGION_AREA_PX) return null
  return { x, y, width, height }
}
