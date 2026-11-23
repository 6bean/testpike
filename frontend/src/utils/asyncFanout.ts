/**
 * Bounded async fan-out — single shared source for "run one thunk per item,
 * at most N concurrently, keep results in item order".
 *
 * Pages that fan a read out per row (the pinned-object panel's pending-count
 * door at worst scale) must not unleash an unbounded browser burst: measured
 * evidence shows a 32-wide burst through the dev-server proxy
 * stretches its tail to seconds and intermittently starves frame-budgeted
 * assertions, even though the same door answers in ~230ms server-side.
 *
 * Semantics:
 * - results keep the input order;
 * - a rejecting thunk never stalls the queue (settled per-item; the caller's
 * thunk normally owns its catch — rejections are surfaced here only for
 * thunks that throw);
 * - non-positive limit is a programming error and rejects immediately.
 */
export async function runBounded<T>(
  tasks: readonly (() => Promise<T>)[],
  limit: number,
): Promise<PromiseSettledResult<T>[]> {
  if (!(limit > 0)) {
    return Promise.reject(new Error(`runBounded: fan-out limit must be > 0, got ${limit}`))
  }
  const settled = new Array<PromiseSettledResult<T>>(tasks.length)
  let next = 0
  const worker = async (): Promise<void> => {
    for (;;) {
      const i = next++
      if (i >= tasks.length) return
      try {
        settled[i] = { status: 'fulfilled', value: await tasks[i]() }
      } catch (reason) {
        settled[i] = { status: 'rejected', reason }
      }
    }
  }
  const lanes = Array.from({ length: Math.min(limit, tasks.length) }, () => worker())
  await Promise.all(lanes)
  return settled
}
