
import { describe, expect, it } from 'vitest'
import { buildGenGroups, genSkeleton, parseParamSchema } from '@/utils/genRef'

describe('parseParamSchema：GenFunction.paramSchema 行 → 结构化参数', () => {
  it('必填参数（text:string=必填原文）', () => {
    const p = parseParamSchema('text:string=必填原文')
    expect(p).toEqual({ name: 'text', type: 'string', optional: false, hint: '必填原文' })
  })
  it('可选参数（名后 ? 号：seed?:long=显式随机种子）', () => {
    const p = parseParamSchema('seed?:long=显式随机种子')
    expect(p).toEqual({ name: 'seed', type: 'long', optional: true, hint: '显式随机种子' })
  })
  it('说明文本含括号与缺省值注记不截断（from?:string=源进制（缺省 10））', () => {
    const p = parseParamSchema('from?:string=源进制（缺省 10）')
    expect(p.name).toBe('from')
    expect(p.optional).toBe(true)
    expect(p.hint).toBe('源进制（缺省 10）')
  })
  it('说明文本含 = 号只按首个 = 切分（zone?:string=时区（缺省 UTC=+0））', () => {
    const p = parseParamSchema('zone?:string=时区（缺省 UTC=+0）')
    expect(p.hint).toBe('时区（缺省 UTC=+0）')
  })
  it('无类型段（仅名字）兜底不炸：返回 name + string 缺省', () => {
    const p = parseParamSchema('text')
    expect(p.name).toBe('text')
    expect(p.optional).toBe(false)
  })
})

describe('genSkeleton：选中函数 → 插入骨架（必填参数留空跳填，可选不进骨架）', () => {
  const fn = (name: string, params: string[]) => ({
    name,
    description: '',
    params,
    example: '',
  })
  it('零参数证件族（email）→ 空括号形态', () => {
    expect(genSkeleton(fn('email', ['seed?:long=显式随机种子']))).toBe('${gen.email()}')
  })
  it('单必填（md5）→ 键留空待填', () => {
    expect(genSkeleton(fn('md5', ['text:string=必填原文']))).toBe('${gen.md5(text=)}')
  })
  it('多必填按声明序（radix：text/to 必填、from 可选不入骨架）', () => {
    expect(genSkeleton(fn('radix', [
      'text:string=必填数字串',
      'from?:string=源进制（缺省 10）',
      'to:string=目标进制',
    ]))).toBe('${gen.radix(text=,to=)}')
  })
  it('骨架整体可被执行器 _GEN_PATTERN 命中（禁脏 token=保存/运行期必红）', () => {
    const GEN_PATTERN = /^\$\{gen\.([A-Za-z][A-Za-z0-9]*)(?:\((.*?)\))?\}$/
    for (const tok of ['${gen.email()}', '${gen.md5(text=)}', '${gen.radix(text=,to=)}']) {
      const m = GEN_PATTERN.exec(tok)
      expect(m, `骨架不被执行器文法命中：${tok}`).not.toBeNull()
    }
  })
})

describe('buildGenGroups：目录 → el-option-group 分组候选（单源透传禁复制清单）', () => {
  it('保持入参分组顺序，叶子带函数名+描述+示例', () => {
    const groups = buildGenGroups([
      { group: '证件族', functions: [{ name: 'email', description: '生成邮箱', params: [], example: 'a@b.com' }] },
      { group: '编码族', functions: [{ name: 'md5', description: 'MD5', params: ['text:string=必填原文'], example: '90015…' }] },
    ])
    expect(groups.map((g) => g.label)).toEqual(['证件族', '编码族'])
    expect(groups[0].options[0]).toMatchObject({ name: 'email', token: '${gen.email()}' })
    expect(groups[1].options[0]).toMatchObject({ name: 'md5', token: '${gen.md5(text=)}' })
  })
  it('空目录=空分组（不造占位假组）', () => {
    expect(buildGenGroups([])).toEqual([])
  })
})
