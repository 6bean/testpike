
import type { ProbeOutcome } from '@/config/journeys'
import { FLOW_DEMO_COLLECTION_NAME } from '@/config/journeys'
import { listCollections, listRequests } from '@/api/apiTest'
import { listDocuments } from '@/api/document'
import { listCases } from '@/api/case'
import { listBatches } from '@/api/batch'
import { listScheduleFires } from '@/api/schedule'
import { listAppautoDevices, listReservations } from '@/api/appauto'
import { listExecutors, listExecTasks } from '@/api/executor'

type ProbeFn = (projectId: number | null) => Promise<ProbeOutcome>


export interface ProbeGate {
  hasPerm: (code: string) => boolean
  projectIds: number[]
}


interface ProbeDef {
  fn: ProbeFn
  permKey: string | null
  projectScoped: boolean
}


async function runProbe(call: () => Promise<number>): Promise<ProbeOutcome> {
  try {
    const count = await call()
    return { ok: true, count }
  } catch {
    return { ok: false, count: 0 }
  }
}

const sizeOne = { page: 1, size: 1 }


const probes: Record<string, ProbeDef> = {
  documents: {
    permKey: null,
    projectScoped: true,
    fn: (pid) => runProbe(async () => (await listDocuments({ projectId: pid!, ...sizeOne })).total),
  },
  cases: {
    permKey: null,
    projectScoped: true,
    fn: (pid) => runProbe(async () => (await listCases({ projectId: pid!, ...sizeOne })).total),
  },
  batches: {
    permKey: 'perm:batch:batch-view',
    projectScoped: true,
    fn: (pid) => runProbe(async () => (await listBatches({ projectId: pid!, ...sizeOne })).total),
  },
  scheduleFires: {
    permKey: null,
    projectScoped: true,
    fn: (pid) => runProbe(async () => (await listScheduleFires({ projectId: pid!, ...sizeOne })).total),
  },
  devices: {
    permKey: 'perm:project:project-view',
    projectScoped: true,
    fn: (pid) => runProbe(async () => (await listAppautoDevices(pid!)).length),
  },
  reservations: {
    permKey: 'perm:appauto:reservation:view',
    projectScoped: true,
    fn: (pid) => runProbe(async () => (await listReservations({ projectId: pid! })).length),
  },
  execTasks: {
    permKey: null,
    projectScoped: true,
    fn: (pid) => runProbe(async () => (await listExecTasks({ projectId: pid!, ...sizeOne })).total),
  },
  executors: {
    permKey: 'perm:project:executor-pool',
    projectScoped: false,
    fn: () => runProbe(async () => (await listExecutors()).length),
  },
  executorOnline: {
    permKey: 'perm:project:executor-pool',
    projectScoped: false,
    fn: () => runProbe(async () => (await listExecutors()).filter((e) => e.status === 1).length),
  },
  // factory demo journey readiness — counts the hops inside the
  // seeded orchestration collection of the CURRENT project (the demo project
  // carries it; any other project honestly reports 0 = not started). Reuses
  // the existing apitest mirrors, zero new endpoint; permission key = the same
  // @RequirePermission code the collection/request GET controllers enforce, so
  // a restricted role never fires a 403 the E2E response-code gate would flag.
  flowDemo: {
    permKey: 'perm:detail:case-tag',
    projectScoped: true,
    fn: async (pid) => runProbe(async () => {
      const cols = await listCollections(pid!)
      const demo = cols.find((c) => c.name === FLOW_DEMO_COLLECTION_NAME)
      if (!demo) return 0
      return (await listRequests(demo.id)).length
    }),
  },
}


export function journeyProbeKeys(): string[] {
  return Object.keys(probes)
}


export function probePermissionKey(key: string): string | null {
  return probes[key]?.permKey ?? null
}


export function probeProjectScoped(key: string): boolean {
  return probes[key]?.projectScoped ?? true
}


export function shouldIssueProbe(key: string, projectId: number | null, gate: ProbeGate): boolean {
  const def = probes[key]
  if (!def) return false
  if (def.permKey && !gate.hasPerm(def.permKey)) return false
  if (def.projectScoped) {
    if (projectId == null) return false
    if (!gate.projectIds.includes(projectId)) return false
  }
  return true
}


export async function runJourneyProbes(keys: string[], projectId: number | null, gate: ProbeGate): Promise<Record<string, ProbeOutcome>> {
  const unique = [...new Set(keys)]
  const entries = await Promise.all(
    unique.map(async (key) => {
      if (!shouldIssueProbe(key, projectId, gate)) return [key, undefined] as const
      return [key, await probes[key].fn(projectId)] as const
    }),
  )
  return Object.fromEntries(entries.filter(([, v]) => v !== undefined)) as Record<string, ProbeOutcome>
}
