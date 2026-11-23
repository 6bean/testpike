/**
 * Page-context builder for the AI chat dock .
 *
 * The dock opens over any page; the session it creates carries a whitelist
 * context so the assistant knows WHERE the user is asking from. Only
 * navigation identifiers ride in (adjudication §context-whitelist):
 * route path, the session's own project segment, one object pointer and one
 * execution id. Filter values, tab names, keyword searches and everything
 * else on the URL never enter the prompt — the server re-validates the same
 * rules at the door, this module is the assembly point on the client side.
 */

/** Query keys that name a single business object the page is focused on. */
const OBJECT_QUERY_KEYS = [
  'objectId',
  'caseId',
  'defectId',
  'requirementId',
  'requestId',
  'runId',
  'batchId',
  'taskId',
  'suiteId',
  'planId',
  'spaceId',
  'scenarioId',
  'docId',
] as const

function scalarId(raw: unknown): string | number | null {
  if (typeof raw === 'string' && raw !== '') return raw
  if (typeof raw === 'number' && Number.isFinite(raw)) return raw
  return null
}

/**
 * Build the context_json string for a new chat session. Returns "{}" only
 * when even the route is missing; projectId always accompanies the segment the
 * header selected (the core door refuses a mismatch).
 *
 * `explicitExecutionId` is the badge deep-link pointer : the id
 * came from the row the user clicked, not from the URL. A query executionId
 * still wins — the page's own address is the fresher statement of focus.
 */
export function buildPageContext(
  routePath: string,
  query: Record<string, unknown>,
  projectId: number,
  explicitExecutionId?: number | string | null,
): string {
  const context: Record<string, string | number> = {
    route: routePath,
    projectId,
  }
  for (const key of OBJECT_QUERY_KEYS) {
    const id = scalarId(query[key])
    if (id !== null) {
      context.objectId = id
      break
    }
  }
  const executionId = scalarId(query['executionId']) ?? scalarId(explicitExecutionId ?? null)
  if (executionId !== null) {
    context.executionId = executionId
  }
  return JSON.stringify(context)
}
