


export interface ProbeOutcome {
  ok: boolean
  count: number
}


export interface JourneyStep {
  probeKey: string
  labelKey: string
  requireHardware?: boolean
}

export interface QuickJourney {
  id: string
  titleKey: string
  icon: string
  
  targetPath: string
  steps: JourneyStep[]
}


export const ONBOARDING_DISMISS_KEY = ['tp', 'onboarding_dismissed'].join('_')

/**
 * Factory demo flow anchors : the quick-journey
 * card and the api-test flow-pane empty-state door both point at the seed
 * shipped by installkit/demo_seed.py (project code=demo-first, collection name
 * carries the tp_demo_ prefix). Deep links stay data-driven (projectCode +
 * collectionName — never numeric ids in URLs): the page resolves them against
 * the visible domain (pruned /me projects), so the link survives reinstalls
 * where ids drift, and simply lands nowhere honest on an env without the seed.
 */
export const FLOW_DEMO_PROJECT_CODE = 'demo-first'
export const FLOW_DEMO_COLLECTION_NAME = 'tp_demo_样例编排流程_下单旅程'

/** Quick journey list: the five frozen plan-A entries + the factory demo flow (sixth, ruling-added; no further add/rename). */
export const quickJourneys: QuickJourney[] = [
  {
    id: 'gen-from-doc',
    titleKey: 'journey.genFromDoc',
    icon: 'Upload',
    targetPath: '/ai/generate',
    steps: [
      { probeKey: 'documents', labelKey: 'journey.stepDoc' },
      { probeKey: 'cases', labelKey: 'journey.stepGenCase' },
    ],
  },
  {
    id: 'run-sample-case',
    titleKey: 'journey.runSampleCase',
    icon: 'VideoPlay',
    targetPath: '/cases',
    steps: [
      { probeKey: 'cases', labelKey: 'journey.stepHasCase' },
      { probeKey: 'execTasks', labelKey: 'journey.stepRunRecord' },
    ],
  },
  {
    id: 'batch-schedule',
    titleKey: 'journey.batchSchedule',
    icon: 'Timer',
    targetPath: '/batches',
    steps: [
      { probeKey: 'batches', labelKey: 'journey.stepCreateBatch' },
      { probeKey: 'scheduleFires', labelKey: 'journey.stepScheduleFired' },
    ],
  },
  {
    id: 'reserve-device',
    titleKey: 'journey.reserveDevice',
    icon: 'Cellphone',
    targetPath: '/appauto/reservations',
    steps: [
      { probeKey: 'devices', labelKey: 'journey.stepDevice', requireHardware: true },
      { probeKey: 'reservations', labelKey: 'journey.stepReservation' },
    ],
  },
  {
    id: 'connect-executor',
    titleKey: 'journey.connectExecutor',
    icon: 'Connection',
    targetPath: '/execution/pools',
    steps: [
      { probeKey: 'executors', labelKey: 'journey.stepExecutor' },
      { probeKey: 'executorOnline', labelKey: 'journey.stepExecutorOnline' },
    ],
  },
  {
    // the factory-seeded four-hop orchestration journey
    // (login -> order -> poll -> paging) in the demo project. targetPath
    // stays on the /api-test menu leaf (query-only deep link, zero new
    // route); the page resolves projectCode/collectionName against the
    // visible domain — an env without the seed lands on the honest empty
    // state, never a fake selection.
    id: 'run-flow-demo',
    titleKey: 'journey.runFlowDemo',
    icon: 'Sort',
    targetPath: `/api-test?view=flow&projectCode=${FLOW_DEMO_PROJECT_CODE}&collectionName=${encodeURIComponent(FLOW_DEMO_COLLECTION_NAME)}`,
    steps: [
      { probeKey: 'flowDemo', labelKey: 'journey.stepFlowDemoJourney' },
    ],
  },
]

export interface JourneyProgress {
  doneSteps: number
  totalSteps: number
  
  percent: number
  
  unreachable: boolean
}


export function computeJourneyProgress(journey: QuickJourney, outcomes: Record<string, ProbeOutcome | undefined>): JourneyProgress {
  const total = journey.steps.length
  let done = 0
  let unreachable = false
  for (const step of journey.steps) {
    const o = outcomes[step.probeKey]
    if (!o) continue
    if (!o.ok) unreachable = true
    else if (o.count > 0) done += 1
  }
  return { doneSteps: done, totalSteps: total, percent: Math.round((done / total) * 100), unreachable }
}


export function missingHardwareSteps(journey: QuickJourney, outcomes: Record<string, ProbeOutcome | undefined>): string[] {
  return journey.steps
    .filter((s) => s.requireHardware && outcomes[s.probeKey]?.ok === true && outcomes[s.probeKey]?.count === 0)
    .map((s) => s.probeKey)
}
