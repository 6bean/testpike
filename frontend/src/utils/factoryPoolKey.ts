


const SCRIPT_KEY_PREFIX = '# poolKey='


const FACTORY_TYPE_SCRIPT = 3


export function extractPoolKey(type: number, content: string): string | null {
  if (!content) return null
  if (type === FACTORY_TYPE_SCRIPT) {
    for (const line of content.split('\n')) {
      if (line.startsWith(SCRIPT_KEY_PREFIX)) {
        const key = line.slice(SCRIPT_KEY_PREFIX.length).trim()
        return key === '' ? null : key
      }
    }
    return null
  }
  try {
    const def = JSON.parse(content) as Record<string, unknown>
    const raw = def?.poolKey
    if (typeof raw !== 'string') return null
    const key = raw.trim()
    return key === '' ? null : key
  } catch {
    return null
  }
}


export function applyPoolKey(type: number, content: string, key: string): string {
  const name = (key ?? '').trim()
  if (type === FACTORY_TYPE_SCRIPT) {
    const lines = (content ?? '').split('\n').filter((l) => !l.startsWith(SCRIPT_KEY_PREFIX))
    if (name === '') return lines.join('\n')
    return `${SCRIPT_KEY_PREFIX}${name}\n${lines.join('\n')}`
  }
  if (name === '') {
    try {
      const parsedClear = JSON.parse(content) as unknown
      if (parsedClear !== null && typeof parsedClear === 'object' && !Array.isArray(parsedClear)) {
        delete (parsedClear as Record<string, unknown>).poolKey
        return JSON.stringify(parsedClear)
      }
    } catch {
      
    }
    return content
  }
  let def: Record<string, unknown>
  try {
    const parsed = JSON.parse(content) as unknown
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
      throw new Error('shape')
    }
    def = parsed as Record<string, unknown>
  } catch {
    throw new Error('造数内容需为合法 JSON 对象才能写入数据键名（请先修正内容或清除键名）')
  }
  def.poolKey = name
  return JSON.stringify(def)
}
