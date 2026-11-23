
import { describe, expect, it } from 'vitest'
import {
  buildPoolRefOptions,
  dataRefToken,
  findDataRefKeys,
  validateDataKey,
  type PoolRefItem,
} from '@/utils/dataRef'

const item = (over: Partial<PoolRefItem>): PoolRefItem => ({
  id: 1,
  projectId: 10,
  envId: 100,
  envName: 'dev',
  type: 'ACCOUNT',
  value: 'u1/p@ss',
  name: 'testUser',
  status: 1,
  ...over,
})

describe('validateDataKey（04 规则6 词法闸镜像，TC-V7-01 非法键人话拒）', () => {
  it('字母/下划线开头标识符 = 合法（null）', () => {
    expect(validateDataKey('sku1')).toBeNull()
    expect(validateDataKey('_order_no')).toBeNull()
  })
  it('数字开头/含连字符/含空格/空白 = 人话点名（含键名回显）', () => {
    for (const bad of ['2bad', 'my-key', 'a b', '']) {
      const err = validateDataKey(bad)
      expect(err, `键「${bad}」应被拒`).toBeTruthy()
      expect(err).toContain(bad === '' ? '键名' : bad)
    }
  })
})

describe('dataRefToken 插入形态（保存链词法闸可过）', () => {
  it('键 → {{DATA:键}} 双花形态（非 ${data.…} 漂移形态）', () => {
    expect(dataRefToken('sku1')).toBe('{{DATA:sku1}}')
  })
})

describe('findDataRefKeys 扫描（IT-V7-02 三命名空间混用互不吞）', () => {
  it('混合文本只提取 DATA 族键，ENV/VAR/PACK/迭代变量不误吞', () => {
    const text = '{"sku":"{{DATA:sku1}}","q":2,"pwd":"{{ENV:PWD}}","x":"{{VAR:3.token}}","i":"{{item}}"}'
    expect(findDataRefKeys(text)).toEqual(['sku1'])
  })
  it('同串多 DATA 键全收且去重保序', () => {
    expect(findDataRefKeys('{{DATA:a}}-{{DATA:b}}-{{DATA:a}}')).toEqual(['a', 'b'])
  })
  it('非法残形（无冒号/截断）不产键', () => {
    expect(findDataRefKeys('{{DATA}} {{DATA:')).toEqual([])
  })
})

describe('buildPoolRefOptions 级联候选（TC-V7-01 池→字段级联 + 样本预览）', () => {
  it('有键名池项 → 引用候选：key/envId/envName/type/sample 透出，样本=value 真值明文', () => {
    const r = buildPoolRefOptions([item({})])
    expect(r.options).toHaveLength(1)
    expect(r.options[0]).toMatchObject({
      key: 'testUser',
      envId: 100,
      envName: 'dev',
      type: 'ACCOUNT',
      sample: 'u1/p@ss',
    })
  })
  it('未落键名（V84 前存量 name 空）项不进插入候选，归 unnamed 清单', () => {
    const r = buildPoolRefOptions([item({ id: 2, name: null }), item({ id: 3, name: '  ' }), item({})])
    expect(r.options.map((o) => o.key)).toEqual(['testUser'])
    expect(r.unnamed.map((u) => u.id)).toEqual([2, 3])
  })
  it('已失效（status=3）池项不进候选（引用失效数据=人话可解释缺口）', () => {
    const r = buildPoolRefOptions([item({ status: 3 }), item({ status: 1 })])
    expect(r.options.map((o) => o.key)).toEqual(['testUser'])
  })
  it('同键名跨环境各自成候选（项目+环境内唯一，非全局唯一）', () => {
    const r = buildPoolRefOptions([
      item({ envId: 100, envName: 'dev' }),
      item({ id: 9, envId: 200, envName: 'sit', value: 'u9' }),
    ])
    expect(r.options).toHaveLength(2)
    expect(r.options.map((o) => o.envName)).toEqual(['dev', 'sit'])
  })
})
