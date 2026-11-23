/**
 * Shared source-tree walker for contract tests (R40 single source: the
 * "list every .vue/.ts file under a directory" shape had seven identical
 * copies across the suites, so it lives here once and every contract test
 * consumes it instead of re-copying it).
 */

import { readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'

/** Every .vue/.ts file path under `dir`, depth-first. */
export function walkSourceFiles(dir: string, out: string[] = []): string[] {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name)
    if (statSync(p).isDirectory()) walkSourceFiles(p, out)
    else if (/\.(vue|ts)$/.test(name)) out.push(p)
  }
  return out
}
