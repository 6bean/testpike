


export interface DescriptorForm {
  name: string
  industry: string
  version: string
  contentPack: string
  defaultSkills: string[]
  
  paramTemplateJson: string
  projectTypes: string[]
}


export const DESCRIPTOR_KEYS = [
  'name', 'industry', 'version', 'content_pack', 'default_skills', 'param_template', 'project_types',
] as const


export const REQUIRED_KEYS = ['name', 'industry', 'version'] as const


export const EXECUTABLE_FORBIDDEN_KEYS = [
  'executable', 'script', 'command', 'code', 'hook', 'hooks',
  'shell', 'python', 'jar', 'class', 'main', 'entrypoint',
]


export const MAX_DESCRIPTOR_BYTES = 64 * 1024

const LIST_JOIN = DESCRIPTOR_KEYS.join(' / ')

function utf8Bytes(s: string): number {
  return new TextEncoder().encode(s).length
}


function findExecutableKey(node: unknown, path: string): string | null {
  if (Array.isArray(node)) {
    for (const item of node) {
      const hit = findExecutableKey(item, path)
      if (hit) return hit
    }
    return null
  }
  if (node && typeof node === 'object') {
    for (const [key, value] of Object.entries(node as Record<string, unknown>)) {
      const childPath = path ? `${path}.${key}` : key
      if (EXECUTABLE_FORBIDDEN_KEYS.includes(key.toLowerCase())) return childPath
      const hit = findExecutableKey(value, childPath)
      if (hit) return hit
    }
  }
  return null
}


export function validateDescriptorForm(form: DescriptorForm): string[] {
  const errors: string[] = []
  const trimmed = { name: form.name.trim(), industry: form.industry.trim(), version: form.version.trim() }
  for (const key of REQUIRED_KEYS) {
    if (!trimmed[key as keyof typeof trimmed]) {
      errors.push(`plugin.yaml 缺少必填键：${key}（必填：${REQUIRED_KEYS.join('/')}；合法键全集：${LIST_JOIN}）`)
    }
  }
  let paramTemplate: unknown
  if (form.paramTemplateJson.trim()) {
    try {
      paramTemplate = JSON.parse(form.paramTemplateJson)
    } catch {
      errors.push('param_template 不是合法 JSON（需为键值映射，如 {"currency":"TWD"}）')
      paramTemplate = undefined
    }
    if (paramTemplate !== undefined && (typeof paramTemplate !== 'object' || paramTemplate === null || Array.isArray(paramTemplate))) {
      errors.push('param_template 顶层需为键值映射（实际为' + (Array.isArray(paramTemplate) ? '序列' : '标量') + '）')
      paramTemplate = undefined
    }
  }
  const execHit = findExecutableKey(paramTemplate, '')
  if (execHit) {
    errors.push(`插件描述符禁可执行字段：${execHit}（H8 安全底线=声明式描述符；可执行能力唯一路径=U 域工具契约+审批）`)
  }
  const content = buildSubmitContent(form)
  if (utf8Bytes(content) > MAX_DESCRIPTOR_BYTES) {
    errors.push('plugin.yaml 超过 64KB 上限（描述符仅承载声明式元数据，正文资产请走内容包/SKILL 通道）')
  }
  return errors
}


export function formToManifest(form: DescriptorForm): Record<string, unknown> {
  const manifest: Record<string, unknown> = {
    name: form.name.trim(),
    industry: form.industry.trim(),
    version: form.version.trim(),
  }
  if (form.contentPack.trim()) manifest.content_pack = form.contentPack.trim()
  if (form.defaultSkills.length) manifest.default_skills = [...form.defaultSkills]
  if (form.paramTemplateJson.trim()) {
    try {
      manifest.param_template = JSON.parse(form.paramTemplateJson)
    } catch {
    }
  }
  if (form.projectTypes.length) manifest.project_types = [...form.projectTypes]
  return manifest
}


export function buildSubmitContent(form: DescriptorForm): string {
  return JSON.stringify(formToManifest(form))
}


export function manifestToForm(manifest: Record<string, unknown> | null): DescriptorForm {
  const m = manifest || {}
  const str = (v: unknown): string =>
    typeof v === 'string' ? v : v == null ? '' : typeof v === 'number' || typeof v === 'boolean' ? String(v) : ''
  const arr = (v: unknown): string[] => (Array.isArray(v) ? v.map((x) => String(x)) : [])
  return {
    name: str(m.name),
    industry: str(m.industry),
    version: str(m.version),
    contentPack: str(m.content_pack),
    defaultSkills: arr(m.default_skills),
    paramTemplateJson: m.param_template == null ? '' : JSON.stringify(m.param_template, null, 2),
    projectTypes: arr(m.project_types),
  }
}
