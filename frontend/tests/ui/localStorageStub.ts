/**
 * Single-source localStorage in-memory stub for node-environment L2 specs
 * (R40 extract-common law — the node env has no localStorage and the Map
 * semantics are identical to the browser one, so every spec that needs a
 * disk stand-in consumes this instead of keeping a private copy).
 */

/** Fresh Map-backed storage object handed to pure-contract specs as a VALUE
 *  (never installed on globalThis): specs whose subject takes a storage
 *  parameter pass this in and read `.dump` to inspect the fake disk. Single
 *  source for that shape — the pin/width/rows/fab dock specs all consume
 *  this instead of keeping a private copy (R40). */
export function createMemoryStorage(initial: Record<string, string> = {}) {
  const map = new Map(Object.entries(initial))
  return {
    getItem: (k: string) => (map.has(k) ? map.get(k)! : null),
    setItem: (k: string, v: string) => map.set(k, v),
    removeItem: (k: string) => map.delete(k),
    dump: map,
  }
}

/** Install a fresh Map-backed localStorage on globalThis; returns the Map
 *  so specs can inspect or reset the "disk" directly. */
export function installLocalStorageStub(): Map<string, string> {
  const store = new Map<string, string>()
  ;(globalThis as Record<string, unknown>).localStorage = {
    getItem: (k: string) => store.get(k) ?? null,
    setItem: (k: string, v: string) => { store.set(k, v) },
    removeItem: (k: string) => { store.delete(k) },
  }
  return store
}
