/**
 * Shared recursive source-walk helper for UI layout contracts.
 *
 * Single source for the "list .vue/.scss/.css files under a directory,
 * skipping node_modules" walk that several UI contract tests need.
 * New contracts must import this helper instead of re-declaring a local
 * walk (R40: a second consumer means one shared component).
 */
import { readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'

export function walkStyleSources(dir: string, out: string[] = []): string[] {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name)
    if (statSync(p).isDirectory()) {
      if (name === 'node_modules') continue
      walkStyleSources(p, out)
    } else if (name.endsWith('.vue') || name.endsWith('.scss') || name.endsWith('.css')) {
      out.push(p)
    }
  }
  return out
}
