
import { describe, expect, it } from 'vitest'
import { applyPoolKey, extractPoolKey } from '@/utils/factoryPoolKey'

describe('extractPoolKey 反查（后端 poolKeyOf 单源镜像）', () => {
  it('type=1/2 JSON 顶层 poolKey 字段 → 键名', () => {
    expect(extractPoolKey(1, '{"poolKey":"sku1","poolType":"ACCOUNT"}')).toBe('sku1')
    expect(extractPoolKey(2, '{"poolKey":"ord_no"}')).toBe('ord_no')
  })
  it('type=3 脚本 → # poolKey= 注释行（前后空白容错）', () => {
    expect(extractPoolKey(3, '# poolKey=sku1\nimport requests')).toBe('sku1')
    expect(extractPoolKey(3, 'import os\n# poolKey= order2 \nprint(1)')).toBe('order2')
  })
  it('缺失/空白值/非法 JSON/非注释行 = null（不可引用态）', () => {
    expect(extractPoolKey(1, '{"poolType":"ACCOUNT"}')).toBeNull()
    expect(extractPoolKey(1, '{"poolKey":"  "}')).toBeNull()
    expect(extractPoolKey(1, 'not-json')).toBeNull()
    expect(extractPoolKey(3, '#poolKey=no-space\n')).toBeNull()
    expect(extractPoolKey(3, 'code # poolKey=inline 不算')).toBeNull()
  })
})

describe('applyPoolKey 组装（保存链路写形态，与 poolKeyOf 往返一致）', () => {
  it('type=1 JSON 设键：往返 extract 一致，保留原字段', () => {
    const out = applyPoolKey(1, '{"poolType":"ACCOUNT","method":"GET"}', 'sku1')
    expect(extractPoolKey(1, out)).toBe('sku1')
    expect(JSON.parse(out).poolType).toBe('ACCOUNT')
  })
  it('type=3 脚本设键：首行注释声明 + 原脚本体零损', () => {
    const out = applyPoolKey(3, 'import requests\nprint(1)', 'sku1')
    expect(extractPoolKey(3, out)).toBe('sku1')
    expect(out).toContain('import requests')
    expect(out).toContain('# poolKey=sku1')
  })
  it('换键=原位替换（反复编辑不产重复行/重复键，幂等）', () => {
    const once = applyPoolKey(3, '# poolKey=a\ncode()', 'b')
    expect(once.split('# poolKey=').length - 1).toBe(1)
    expect(extractPoolKey(3, once)).toBe('b')
    const json = applyPoolKey(1, '{"poolKey":"a","x":1}', 'b')
    expect(JSON.parse(json)).toEqual({ x: 1, poolKey: 'b' })
  })
  it('键清空=移除声明（type=3 删注释行；JSON 删字段；均往返 null）', () => {
    const out3 = applyPoolKey(3, '# poolKey=a\ncode()', '')
    expect(out3).toBe('code()')
    expect(extractPoolKey(3, out3)).toBeNull()
    const outJson = applyPoolKey(1, '{"poolKey":"a","x":1}', '')
    expect(JSON.parse(outJson)).toEqual({ x: 1 })
    expect(extractPoolKey(1, outJson)).toBeNull()
  })
  it('type=1 非法 JSON 不吞内容：抛人话点名（保存前表单拦，禁静默改脏 content）', () => {
    expect(() => applyPoolKey(1, 'not-json', 'k')).toThrow(/JSON/)
  })
})
