
import { describe, expect, it } from 'vitest'
import {
  buildSubmitContent,
  formToManifest,
  manifestToForm,
  validateDescriptorForm,
} from '@/utils/pluginDescriptor'

function validForm() {
  return {
    name: 'pos-retail',
    industry: 'POS',
    version: '1.0.1',
    contentPack: 'pos-retail-pack',
    defaultSkills: ['POS 收银主流程用例规范'],
    paramTemplateJson: '{\n  "currency": "TWD"\n}',
    projectTypes: ['pos'],
  }
}

describe('validateDescriptorForm（镜像 core 七键/必填/可执行族/类型）', () => {
  it('合法描述符 → 零错误', () => {
    expect(validateDescriptorForm(validForm())).toEqual([])
  })

  it('必填三键缺失 → 逐字段点名（含键名，非笼统文案）', () => {
    const errs = validateDescriptorForm({ ...validForm(), name: '  ', industry: '', version: 'x' })
    expect(errs.some((e) => e.includes('缺少必填键：name'))).toBe(true)
    expect(errs.some((e) => e.includes('缺少必填键：industry'))).toBe(true)
    expect(errs.some((e) => e.includes('缺少必填键：version'))).toBe(false)
  })

  it('param_template 非法 JSON → 点名该字段人话', () => {
    const errs = validateDescriptorForm({ ...validForm(), paramTemplateJson: '{oops' })
    expect(errs.some((e) => e.includes('param_template'))).toBe(true)
  })

  it('param_template 顶层非映射（数组/标量）→ 拒', () => {
    expect(validateDescriptorForm({ ...validForm(), paramTemplateJson: '[1,2]' }).length).toBeGreaterThan(0)
    expect(validateDescriptorForm({ ...validForm(), paramTemplateJson: '"str"' }).length).toBeGreaterThan(0)
  })

  it('可执行族键名嵌套命中 → 路径点名（镜像 core rejectExecutable 嵌套拦截）', () => {
    const errs = validateDescriptorForm({
      ...validForm(),
      paramTemplateJson: '{"a":{"script":"rm -rf"}}',
    })
    expect(errs.some((e) => e.includes('a.script'))).toBe(true)
  })

  it('可执行族键名大小写不敏感命中', () => {
    const errs = validateDescriptorForm({ ...validForm(), paramTemplateJson: '{"HOOKS":{}}' })
    expect(errs.some((e) => /hooks/i.test(e))).toBe(true)
  })

  it('超 64KB 上限 → 拒（镜像 core MAX_BYTES）', () => {
    const big = { ...validForm(), paramTemplateJson: JSON.stringify({ k: 'x'.repeat(70 * 1024) }) }
    expect(validateDescriptorForm(big).length).toBeGreaterThan(0)
  })
})

describe('manifestToForm / formToManifest / buildSubmitContent（回显与提交装配）', () => {
  it('core 归一化 manifest → 表单回填（裸标量数值收编为字符串）', () => {
    const f = manifestToForm({ name: 'n', industry: 'POS', version: 1, default_skills: ['s1', 's2'] })
    expect(f.name).toBe('n')
    expect(f.version).toBe('1')
    expect(f.defaultSkills).toEqual(['s1', 's2'])
    expect(f.paramTemplateJson).toBe('')
  })

  it('formToManifest 只出七键（键名=core 权威拼写，禁 description 等自造键）', () => {
    const m = formToManifest(validForm())
    expect(Object.keys(m).sort()).toEqual(
      ['content_pack', 'default_skills', 'industry', 'name', 'param_template', 'project_types', 'version'],
    )
  })

  it('可选键留空 → 提交内容省略（core requireType 只查在位值）', () => {
    const m = formToManifest({ ...validForm(), contentPack: '', defaultSkills: [], projectTypes: [], paramTemplateJson: '' })
    expect(m).not.toHaveProperty('content_pack')
    expect(m).not.toHaveProperty('default_skills')
    expect(m).not.toHaveProperty('project_types')
    expect(m).not.toHaveProperty('param_template')
  })

  it('buildSubmitContent=合法 YAML 子集（JSON 文本），SnakeYAML SafeConstructor 可解析', () => {
    const content = buildSubmitContent(validForm())
    const back = JSON.parse(content)
    expect(back.name).toBe('pos-retail')
    expect(back.param_template).toEqual({ currency: 'TWD' })
  })
})
