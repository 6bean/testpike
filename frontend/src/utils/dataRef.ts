


export interface PoolRefItem {
  id: number
  projectId: number
  envId: number
  envName: string
  type: string
  
  value: string
  
  name: string | null
  
  status: number
}


export interface PoolRefOption {
  key: string
  envId: number
  envName: string
  type: string
  sample: string
}


const DATA_KEY_PATTERN = /^[A-Za-z_][A-Za-z0-9_]*$/


export function validateDataKey(key: string): string | null {
  if (!key || !key.trim()) {
    return '数据键名不能为空：请先在数据池中为池项补录键名后再引用'
  }
  if (!DATA_KEY_PATTERN.test(key)) {
    return `数据键名「${key}」不合法：仅允许字母或下划线开头的标识符（字母/数字/下划线，数字不能开头）`
  }
  return null
}


export function dataRefToken(key: string): string {
  return '{'.repeat(2) + 'DATA:' + key + '}'.repeat(2)
}


const DATA_TOKEN_GLOBAL = /\{\{DATA:([^{}]*)}}/g


export function findDataRefKeys(text: string): string[] {
  const keys: string[] = []
  for (const m of text.matchAll(DATA_TOKEN_GLOBAL)) {
    const key = m[1]
    if (DATA_KEY_PATTERN.test(key) && !keys.includes(key)) keys.push(key)
  }
  return keys
}


export interface PoolRefOptionsResult {
  options: PoolRefOption[]
  unnamed: PoolRefItem[]
}


export function buildPoolRefOptions(items: PoolRefItem[]): PoolRefOptionsResult {
  const options: PoolRefOption[] = []
  const unnamed: PoolRefItem[] = []
  for (const it of items) {
    const key = (it.name ?? '').trim()
    if (!key || validateDataKey(key) || it.status === 3) {
      unnamed.push(it)
      continue
    }
    options.push({ key, envId: it.envId, envName: it.envName, type: it.type, sample: it.value })
  }
  return { options, unnamed }
}
