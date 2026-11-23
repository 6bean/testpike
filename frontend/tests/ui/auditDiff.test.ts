
import { describe, expect, it } from 'vitest'
import { buildAuditDiffRows, hasNoSnapshot } from '../../src/utils/auditDiff'

const SNAP = '(快照)'

describe('buildAuditDiffRows 四形态快照', () => {
  it('双 null（事件类无快照行）=空数组，不抛 TypeError', () => {
    expect(buildAuditDiffRows(null, null, SNAP)).toEqual([])
    expect(hasNoSnapshot(null, null)).toBe(true)
  })

  it('单侧 null（如 delete 仅 before）=单行展示不误入 keys 分支', () => {
    const rows = buildAuditDiffRows('{"configKey":"k1"}', null, SNAP)
    expect(rows).toHaveLength(1)
    expect(rows[0].changed).toBe(true)
    expect(rows[0].before).toBe('{"configKey":"k1"}')
    expect(rows[0].after).toBe(null)
  })

  it('数组快照=单行展示（禁 Object.keys 数组化）', () => {
    const rows = buildAuditDiffRows('[1,2]', '[1,3]', SNAP)
    expect(rows).toHaveLength(1)
    expect(rows[0].field).toBe(SNAP)
    expect(rows[0].changed).toBe(true)
    expect(rows[0].before).toBe('[1,2]')
  })

  it('字符串快照（非 JSON）=单行原样展示', () => {
    const rows = buildAuditDiffRows('plain-text', 'plain-text', SNAP)
    expect(rows).toHaveLength(1)
    expect(rows[0].changed).toBe(false)
    expect(rows[0].before).toBe('plain-text')
  })

  it('双对象=逐字段对照，变更字段 changed=true，仅一侧存在的字段并入', () => {
    const rows = buildAuditDiffRows(
      '{"configValue":"old","removed":"x"}',
      '{"configValue":"new","added":"y"}',
      SNAP,
    )
    const byField = Object.fromEntries(rows.map((r) => [r.field, r]))
    expect(byField.configValue.changed).toBe(true)
    expect(byField.removed.after).toBe(null)
    expect(byField.added.before).toBe(null)
  })

  it('after 为 {"value":{...}} 包装时解包后按字段对比', () => {
    const rows = buildAuditDiffRows(
      '{"configValue":"old"}',
      '{"value":{"configValue":"new"}}',
      SNAP,
    )
    expect(rows).toHaveLength(1)
    expect(rows[0].field).toBe('configValue')
    expect(rows[0].changed).toBe(true)
  })

  it('hasNoSnapshot：空串快照（after=""）同视 null', () => {
    expect(hasNoSnapshot(null, '')).toBe(true)
    expect(hasNoSnapshot('{"a":1}', '')).toBe(false)
  })
})
