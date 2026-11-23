/**
 * Single source for the T294d fleet contract suites' read-of-record.
 *
 * The three sibling suites (elasticity overview, node matrix, scale-event
 * timeline) each pin a different band of the executor-pool page but must read
 * the very same two under-test files. Reading them here once keeps the
 * boilerplate from drifting per suite (R40: a copy is how drift starts — a
 * later suite could silently read a different path and go falsely green).
 */
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

const SRC = join(__dirname, '..', '..', 'src')

/** Admin fleet bridge mirror as typed on the frontend (api/cluster.ts). */
export const apiSrc = readFileSync(join(SRC, 'api', 'cluster.ts'), 'utf8')

/** The resource-pool page under test (ExecutorPoolView). */
export const viewSrc = readFileSync(join(SRC, 'views', 'execution', 'ExecutorPoolView.vue'), 'utf8')

/** The add-pool wizard component (step flow + enroll-token bridge consumption).
 *  Read-of-record for fleetPoolWizard; a missing file reads as '' so the suite
 *  fails with named assertions instead of a loader crash. */
export const wizardSrc = (() => {
  try {
    return readFileSync(join(SRC, 'views', 'execution', 'FleetPoolWizard.vue'), 'utf8')
  } catch {
    return ''
  }
})()

/** Fleet installer key template (deploy/fleet/fleet-keys.env.example) — the
 *  documented key surface of the deploy/fleet scripts. Read-of-record for the
 *  wizard's access-command parity contract: the page assembles command text,
 *  every key it names must already be documented here (R40 — the wizard may
 *  never invent a second key set; tools/install_config_gate.py keeps the
 *  scripts and this file in parity, this helper keeps the wizard in parity). */
export const fleetKeysSrc = (() => {
  try {
    return readFileSync(join(__dirname, '..', '..', '..', 'deploy', 'fleet', 'fleet-keys.env.example'), 'utf8')
  } catch {
    return ''
  }
})()

/** The assignment keys documented in fleet-keys.env.example as a Set. */
export function fleetDocumentedKeys(src: string): Set<string> {
  const keys = new Set<string>()
  for (const line of src.split('\n')) {
    const m = line.match(/^([A-Z0-9_]+)=/)
    if (m) keys.add(m[1])
  }
  return keys
}
