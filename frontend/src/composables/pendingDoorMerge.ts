/**
 * Pending-door merge for the pinned panel .
 *
 * The group door keeps its acceptance receipt in its OWN component state, so
 * unmounting the door on reload silently destroys the answer the human just
 * paid a one-shot ticket for (observed in the linkage spectrum E2E: apply
 * landed server-side, the receipt never painted). This pure merge decides,
 * after each server re-read, which doors ride on:
 *
 * - a door the server still lists always takes the fresh row (server truth);
 * - an answered door the server no longer lists is retained with its
 * previous cards, keyed unchanged so the SAME component instance survives
 * and its receipt stays readable;
 * - retention is scoped to the current project segment: switching slices is a
 * new truth and stale receipts from the old segment must not follow it.
 *
 * Retained doors are excluded from the pending count by the caller — they
 * report an answer, they do not ask for one.
 */
export interface DoorKeyed {
  projectId: number
  objectType: string
}

export function doorKey(door: DoorKeyed): string {
  return `${door.projectId}:${door.objectType}`
}

export function mergePendingDoors<T extends DoorKeyed>(
  fresh: T[],
  previous: T[],
  answeredKeys: ReadonlySet<string>,
  projectId: number,
): T[] {
  const freshKeys = new Set(fresh.map(doorKey))
  const retained = previous.filter(
    (door) =>
      door.projectId === projectId &&
      answeredKeys.has(doorKey(door)) &&
      !freshKeys.has(doorKey(door)),
  )
  return [...fresh, ...retained]
}
