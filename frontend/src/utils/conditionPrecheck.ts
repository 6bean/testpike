

import { i18n } from '@/utils/i18n'


function tv(key: string, params?: Record<string, unknown>): string {
  return String(i18n.global.t(key, params ?? {}))
}

export interface ConditionCheck {
  ok: boolean
  
  error?: string
}

type TokKind = 'num' | 'str' | 'bool' | 'null' | 'ident' | 'op' | 'eof'

interface Tok {
  kind: TokKind
  value: unknown
  pos: number
}


const KEYWORDS: Record<string, [TokKind, unknown]> = {
  true: ['bool', true],
  false: ['bool', false],
  null: ['null', null],
}
const LOGIC_WORDS = ['and', 'or', 'not']

const TOKEN_RE = new RegExp(
  String.raw`\s*(?:` +
    String.raw`(?<num>\d+(?:\.\d+)?)` +
    String.raw`|(?<str>'(?:[^'\\]|\\.)*'|"(?:[^"\\]|\\.)*")` +
    String.raw`|(?<word>[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*)` +
    String.raw`|(?<op>==|!=|<=|>=|&&|\|\||[<>()!])` +
    String.raw`|(?<bad>\S))`,
  'g',
)

function unquote(raw: string): string {
  const body = raw.slice(1, -1)
  let out = ''
  for (let i = 0; i < body.length; i++) {
    if (body[i] === '\\' && i + 1 < body.length) {
      const map: Record<string, string> = { n: '\n', t: '\t', '\\': '\\', "'": "'", '"': '"' }
      out += map[body[i + 1]] ?? body[i + 1]
      i++
    } else {
      out += body[i]
    }
  }
  return out
}


type Node =
  | { kind: 'lit'; lit: 'num' | 'str' | 'bool' | 'null'; value: unknown; pos: number }
  | { kind: 'var'; path: string[]; pos: number }
  | { kind: 'not'; expr: Node }
  | { kind: 'logic'; op: 'or' | 'and'; left: Node; right: Node }
  | { kind: 'cmp'; op: string; left: Node; right: Node; pos: number }

class PrecheckError extends Error {}

function tokenize(text: string): Tok[] {
  const toks: Tok[] = []
  let pos = 0
  while (pos < text.length) {
    TOKEN_RE.lastIndex = pos
    const m = TOKEN_RE.exec(text)
    if (!m || m.index !== pos || m[0].length === 0) {
      throw new PrecheckError(tv('vocab.err.badChar', { char: text[pos], pos }))
    }
    pos = TOKEN_RE.lastIndex
    const g = m.groups as Record<string, string | undefined>
    if (g.bad !== undefined) {
      throw new PrecheckError(tv('vocab.err.grammarChar', { bad: g.bad }))
    }
    if (g.word !== undefined) {
      const word = g.word
      const wpos = (m.index + m[0].indexOf(word)) | 0
      if (!word.includes('.') && word in KEYWORDS) {
        const [kind, value] = KEYWORDS[word]
        toks.push({ kind, value, pos: wpos })
      } else if (!word.includes('.') && LOGIC_WORDS.includes(word)) {
        toks.push({ kind: 'op', value: word, pos: wpos })
      } else if (word.includes('__')) {
        throw new PrecheckError(tv('vocab.err.dunder', { word }))
      } else {
        toks.push({ kind: 'ident', value: word, pos: wpos })
      }
    } else if (g.num !== undefined) {
      toks.push({ kind: 'num', value: Number(g.num), pos: m.index + (m[0].indexOf(g.num) | 0) })
    } else if (g.str !== undefined) {
      toks.push({ kind: 'str', value: unquote(g.str), pos: m.index + (m[0].indexOf(g.str) | 0) })
    } else {
      toks.push({ kind: 'op', value: g.op, pos: m.index + (m[0].indexOf(g.op as string) | 0) })
    }
  }
  toks.push({ kind: 'eof', value: null, pos })
  return toks
}

class Parser {
  private i = 0
  constructor(private readonly toks: Tok[], private readonly knownVars: string[] | null) {}

  parse(): Node {
    const node = this.orExpr()
    if (this.peek().kind !== 'eof') {
      throw new PrecheckError(tv('vocab.err.trailing', { pos: this.peek().pos, tok: describe(this.peek()) }))
    }
    return node
  }

  private peek(): Tok {
    return this.toks[this.i]
  }

  private next(): Tok {
    const t = this.toks[this.i]
    this.i += 1
    return t
  }

  private isOp(t: Tok, ...ops: string[]): boolean {
    return t.kind === 'op' && ops.includes(t.value as string)
  }

  private orExpr(): Node {
    let left = this.andExpr()
    while (this.isOp(this.peek(), '||', 'or')) {
      this.next()
      left = { kind: 'logic', op: 'or', left, right: this.andExpr() }
    }
    return left
  }

  private andExpr(): Node {
    let left = this.notExpr()
    while (this.isOp(this.peek(), '&&', 'and')) {
      this.next()
      left = { kind: 'logic', op: 'and', left, right: this.notExpr() }
    }
    return left
  }

  private notExpr(): Node {
    const t = this.peek()
    if (this.isOp(t, '!', 'not')) {
      this.next()
      return { kind: 'not', expr: this.notExpr() }
    }
    return this.cmpExpr()
  }

  private cmpExpr(): Node {
    const left = this.primary()
    const t = this.peek()
    if (this.isOp(t, '==', '!=', '<', '<=', '>', '>=')) {
      this.next()
      const right = this.primary()
      this.assertCmp(t.value as string, left, right, t.pos)
      return { kind: 'cmp', op: t.value as string, left, right, pos: t.pos }
    }
    return left
  }

  
  private assertCmp(op: string, left: Node, right: Node, pos: number): void {
    if (left.kind !== 'lit' || right.kind !== 'lit') return
    const numLike = (n: Node) => n.kind === 'lit' && (n.lit === 'num' || n.lit === 'bool')
    if (['<', '<=', '>', '>='].includes(op)) {
      if (!numLike(left) || !numLike(right)) {
        throw new PrecheckError(tv('vocab.err.operand', { pos }))
      }
      return
    }
    const crossType =
      (left.lit === 'str' && (right.lit === 'num' || right.lit === 'bool')) ||
      ((left.lit === 'num' || left.lit === 'bool') && right.lit === 'str')
    if (op === '==' && crossType) {
      throw new PrecheckError(tv('vocab.err.typeEq', { pos, left: left.value, right: right.value }))
    }
  }

  private primary(): Node {
    const t = this.next()
    if (t.kind === 'num' || t.kind === 'str' || t.kind === 'bool' || t.kind === 'null') {
      return { kind: 'lit', lit: t.kind, value: t.value, pos: t.pos }
    }
    if (t.kind === 'op' && t.value === '(') {
      const inner = this.orExpr()
      const close = this.next()
      if (!(close.kind === 'op' && close.value === ')')) {
        throw new PrecheckError(tv('vocab.err.expectParen', { pos: close.pos, tok: describe(close) }))
      }
      return inner
    }
    if (t.kind === 'ident') {
      const path = String(t.value).split('.')
      if (this.knownVars !== null) {
        const head = path[0]
        if (!this.knownVars.includes(head)) {
          const defined = [...this.knownVars].sort().join('、') || tv('vocab.err.noVars')
          throw new PrecheckError(tv('vocab.err.undefVar', { path: path.join('.'), pos: t.pos, defined }))
        }
      }
      return { kind: 'var', path, pos: t.pos }
    }
    throw new PrecheckError(tv('vocab.err.expectValue', { pos: t.pos, tok: describe(t) }))
  }
}

function describe(t: Tok): string {
  return t.kind === 'eof' ? tv('vocab.err.eofLabel') : `'${t.value}'`
}


export function checkCondition(expr: string, knownVars?: string[] | null): ConditionCheck {
  if (expr == null || !String(expr).trim()) {
    return { ok: false, error: tv('vocab.err.missingCondition') }
  }
  try {
    new Parser(tokenize(String(expr)), knownVars ?? null).parse()
    return { ok: true }
  } catch (e) {
    if (e instanceof PrecheckError) return { ok: false, error: e.message }
    throw e
  }
}
