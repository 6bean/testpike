/**
 * segment c-4 U-4 journey-card editing model (display + declaration helpers).
 *
 * Pure mirror of the server grouping single source
 * (core PerfMixGroups.derive / linkRate): steps carrying a non-blank
 * {@code transaction} collapse into pressure links in declaration order,
 * same-name steps merge, the weight is the first declaration. This module
 * owns NO validation — the server save gate (PerfStepActionGate
 * .mixStructureViolation) stays the only authority; the helpers here only
 * make it structurally impossible to produce the refused shapes from the UI
 * (enable = every step joins a link; clear = both keys leave every step).
 *
 * The donut/share percentages are a reading of the weight fraction, shown
 * next to the slider so the author sees what share of the total pressure
 * each link gets — display-only, never a scheduling claim.
 */

/** One wizard step row (structural subset — the wizard owns the full shape). */
export interface PerfStepRow {
  action: string
  method?: string
  url?: string
  auth?: boolean
  headers?: Record<string, string>
  body?: unknown
  saveAs?: string
  expect?: unknown
  // B-3 mix declaration : link name + link weight, positive int.
  transaction?: string
  weight?: number
}

/** One journey card = one pressure link derived from the step array. */
export interface JourneyLink {
  name: string
  /** 0 = the whole group carries no weight anywhere (even split). */
  weight: number
  /** Indices into the original step array, chain order preserved. */
  indices: number[]
}

/**
 * Worst-scale guard (display-scale iron rule): 500-step scenarios can declare many
 * links; only this many cards render before an honest fold line points at
 * the count of the rest (the step table below still carries every row).
 */
export const JOURNEY_CARD_CAP = 12

/**
 * Mirror of PerfMixGroups.derive: blank transaction = not part of a link;
 * same name merges; weight rides the first non-zero declaration in the
 * group (0 when the group is entirely unweighted).
 */
export function deriveJourneyGroups(steps: PerfStepRow[]): JourneyLink[] {
  const links: JourneyLink[] = []
  steps.forEach((step, i) => {
    const name = (step.transaction ?? '').trim()
    if (!name) return
    const weight = typeof step.weight === 'number' && step.weight > 0 ? step.weight : 0
    const existing = links.find((l) => l.name === name)
    if (existing) {
      existing.indices.push(i)
      if (existing.weight === 0) existing.weight = weight
    } else {
      links.push({ name, weight, indices: [i] })
    }
  })
  return links
}

/**
 * Mirror of PerfMixGroups.linkRate share reading: weight / sum-of-weights,
 * an all-unweighted set splits evenly. Rounded to whole percents — the
 * donut is a reading aid, the engines re-derive exact rates server-side.
 */
export function journeySharePct(link: JourneyLink, links: JourneyLink[]): number {
  if (links.length === 0) return 100
  const total = links.reduce((sum, l) => sum + l.weight, 0)
  if (total === 0) return Math.round(100 / links.length)
  return Math.round((100 * link.weight) / total)
}

/**
 * Keep the whole-scenario invariant on growth: when the mix is declared, a
 * new step joins the LAST link at weight 1 (server gate would refuse an
 * undeclared step in a declared scenario — enableJourneyDeclaration is the
 * same single source as the card-zone enable switch).
 */
export function joinLastLink(steps: PerfStepRow[]): void {
  const links = deriveJourneyGroups(steps)
  if (links.length === 0) return
  const last = links[links.length - 1]
  const added = steps[steps.length - 1]
  if (added && !(added.transaction ?? '').trim()) {
    added.transaction = last.name
    // Join at the group's declared weight (the save gate refuses two
    // weights in one link); an unweighted group gains no weight from a join.
    if (last.weight > 0) added.weight = last.weight
  }
}

/**
 * Enter the mix declaration: steps without a link join the given name at
 * weight 1 (the server gate refuses a half-declared scenario, so enabling
 * must complete the declaration in one action). Steps already in links keep
 * their link and weight untouched.
 */
export function enableJourneyDeclaration(steps: PerfStepRow[], linkName: string): void {
  const name = linkName.trim() || 'A'
  steps.forEach((step) => {
    const current = (step.transaction ?? '').trim()
    if (!current) {
      step.transaction = name
      if (typeof step.weight !== 'number' || step.weight <= 0) step.weight = 1
    }
  })
}

/**
 * Leave the mix declaration: strip BOTH keys from every step together —
 * an orphan weight is exactly what the server save gate rejects, so the UI
 * never leaves one behind (clear is all-or-nothing by construction).
 */
export function clearJourneyDeclaration(steps: PerfStepRow[]): void {
  steps.forEach((step) => {
    delete step.transaction
    delete step.weight
  })
}

/**
 * One step → save payload (shared by the wizard buildPayload): keep the
 * GA-1/ full-round-trip rule (never whitelist fields away) and add the
 * B-3 pair, dropped only when blank. A blank transaction drops its weight
 * with it so the payload can never trip the orphan-weight refusal.
 */
export function stepForSave(r: PerfStepRow): Record<string, unknown> {
  const base: Record<string, unknown> =
    r.action === 'http.request'
      ? { action: r.action, method: r.method, url: (r.url ?? '').trim() }
      : { action: r.action }
  if (r.auth) base.auth = true
  for (const key of ['headers', 'body', 'saveAs', 'expect'] as const) {
    const v = r[key]
    const filled = v !== undefined && v !== null && v !== ''
      && !(typeof v === 'object' && !Array.isArray(v) && Object.keys(v as object).length === 0)
    if (filled) base[key] = v
  }
  const tx = (r.transaction ?? '').trim()
  if (tx) {
    base.transaction = tx
    if (typeof r.weight === 'number' && r.weight > 0) base.weight = r.weight
  }
  return base
}
