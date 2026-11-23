
import type { ScopeStep } from '@/utils/stepScope'


export const DEFAULT_LOOP_VAR = 'item'


const VAR_NAME_RE = /^[A-Za-z_][A-Za-z0-9_]*$/


export interface ShellResult {
  ok: boolean
  step?: ScopeStep
  message?: string
}


export function makeLoopShell(visibleVars: string[], varName?: string): ShellResult {
  const custom = typeof varName === 'string' ? varName.trim() : ''
  if (varName !== undefined && varName !== null && custom && !VAR_NAME_RE.test(custom)) {
    return {
      ok: false,
      message: `循环变量名「${varName}」不合法：需以字母或下划线开头，仅含字母、数字、下划线`,
    }
  }
  const name = custom || DEFAULT_LOOP_VAR
  if (visibleVars.includes(name)) {
    return {
      ok: false,
      message: `循环变量名 "${name}" 与外层循环的迭代变量冲突，请换一个名字`,
    }
  }
  const step: ScopeStep = { action: 'loop', items: [], steps: [] }
  if (custom) step.var = custom
  return { ok: true, step }
}


export function makeIfShell(): ScopeStep {
  return { action: 'if', condition: '', steps: [], elseSteps: [] }
}


export function makeCodeShell(): ScopeStep {
  return { action: 'code.step', language: 'python', code: '' }
}
