/**
 * Program lens header context — node-level L2 contract for the
 * programContext store plus the auth-store snapshot mapping.
 * Contracts pinned here:
 * - Zero-touch rule: a user with no programs sees exactly today's UI —
 * programOptions empty, inScope always true (the lens never filters
 * anything until a program is actually picked).
 * - Persistence = localStorage key tp_program_ctx_<userId> (no cross-account
 * bleed), cleared on logout; only positive integers are trusted from disk.
 * - Silent relax: when the program snapshot (auth.programs, replaced wholesale
 * by loadMe) no longer contains the active id, the lens drops to null
 * immediately and persists — no toast, no blocking dialog (switcher
 * zero-disturbance rule from close-out).
 * - Hydration idempotence: re-loading equal content fires no watcher (the
 * value stays the same reference-free scalar; watchers must not see a
 * fake "user changed the lens" event).
 * Division of labour: header rendering / tri-state mount lives in the comp
 * layer (tests/comp/programHeaderMount.test.ts); full-chain behaviour = E2E.
 */
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi, type MockInstance } from 'vitest'
import { nextTick, watch } from 'vue'
import type { LoginUser, UserProgramView } from '@/api/auth'

vi.mock('@/api/auth', async () => (await import('./authApiMockHarness')).authApiMock())
vi.mock('@/api/rbac', async () => (await import('./authApiMockHarness')).rbacApiMock())

import * as authApi from '@/api/auth'
import { useAuthStore } from '@/stores/auth'
import { PROGRAM_CTX_KEY_PREFIX, useProgramContextStore } from '@/stores/programContext'
import { installLocalStorageStub } from './localStorageStub'

function user(uid: number): LoginUser {
  return { userId: uid, username: `u${uid}` } as LoginUser
}

function program(programId: number, projectIds: number[]): UserProgramView {
  return { programId, name: `Program ${programId}`, projectIds }
}

let disk: Map<string, string>
let auth: ReturnType<typeof useAuthStore>
let lens: ReturnType<typeof useProgramContextStore>

beforeEach(() => {
  disk = installLocalStorageStub()
  setActivePinia(createPinia())
  auth = useAuthStore()
  lens = useProgramContextStore()
})

afterEach(() => {
  delete (globalThis as Record<string, unknown>).localStorage
  vi.clearAllMocks()
})

describe('programContext store — lens off / zero-touch', () => {
  it('no programs at all: options empty, inScope true for any project', () => {
    auth.user = user(7)
    auth.programs = []
    expect(lens.programOptions).toEqual([])
    expect(lens.activeProgramId).toBeNull()
    expect(lens.scopedProjectIds).toBeNull()
    expect(lens.inScope(999)).toBe(true)
  })

  it('programs exist but none picked: lens is off, inScope still true', () => {
    auth.user = user(7)
    auth.programs = [program(5, [11, 22])]
    expect(lens.programOptions).toEqual([{ programId: 5, name: 'Program 5' }])
    expect(lens.inScope(33)).toBe(true)
  })
})

describe('programContext store — scoping and persistence', () => {
  it('select(id): scoped set mirrors the program snapshot, persisted per user', () => {
    auth.user = user(7)
    auth.programs = [program(5, [11, 22]), program(6, [33])]
    lens.select(5)
    expect(lens.activeProgramId).toBe(5)
    expect(lens.scopedProjectIds).toEqual(new Set([11, 22]))
    expect(lens.inScope(11)).toBe(true)
    expect(lens.inScope(33)).toBe(false)
    expect(disk.get(`${PROGRAM_CTX_KEY_PREFIX}7`)).toBe('5')
  })

  it('select(null): lens off again, disk follows', () => {
    auth.user = user(7)
    auth.programs = [program(5, [11])]
    lens.select(5)
    lens.select(null)
    expect(lens.activeProgramId).toBeNull()
    expect(lens.inScope(11)).toBe(true)
    expect(disk.get(`${PROGRAM_CTX_KEY_PREFIX}7`)).toBe('null')
  })

  it('dirty disk payloads are rejected (never trusted as an id)', async () => {
    auth.user = user(7)
    await nextTick()
    for (const raw of ['[3]', 'abc', '-5', '0', '1.5', '{"a":1}', '']) {
      disk.set(`${PROGRAM_CTX_KEY_PREFIX}7`, raw)
      lens.loadForUser()
      expect(lens.activeProgramId).toBeNull()
    }
    disk.set(`${PROGRAM_CTX_KEY_PREFIX}7`, '5')
    lens.loadForUser()
    expect(lens.activeProgramId).toBe(5)
  })

  it('per-user keys never bleed into each other', async () => {
    auth.user = user(7)
    await nextTick()
    lens.select(5)
    auth.user = user(8)
    await nextTick()
    expect(lens.activeProgramId).toBeNull()
    expect(disk.get(`${PROGRAM_CTX_KEY_PREFIX}7`)).toBe('5')
    auth.user = user(7)
    await nextTick()
    expect(lens.activeProgramId).toBe(5)
  })

  it('logout clears memory and removes the previous user key', async () => {
    auth.user = user(7)
    lens.select(5)
    await auth.logout()
    await nextTick()
    expect(lens.activeProgramId).toBeNull()
    expect(disk.has(`${PROGRAM_CTX_KEY_PREFIX}7`)).toBe(false)
  })
})

describe('programContext store — snapshot replacement and idempotence', () => {
  it('active program vanishing from the snapshot relaxes to null silently', async () => {
    auth.user = user(7)
    auth.programs = [program(5, [11]), program(6, [22])]
    lens.select(6)
    // loadMe replaces the whole array; program 6 no longer exists for this user
    auth.programs = [program(5, [11])]
    await nextTick()
    expect(lens.activeProgramId).toBeNull()
    expect(disk.get(`${PROGRAM_CTX_KEY_PREFIX}7`)).toBe('null')
  })

  it('re-hydration of equal content wakes no watchers', async () => {
    auth.user = user(7)
    auth.programs = [program(5, [11])]
    lens.select(5)
    await nextTick()
    const spy = vi.fn()
    watch(() => lens.activeProgramId, spy)
    lens.loadForUser()
    lens.loadForUser()
    await nextTick()
    expect(spy).not.toHaveBeenCalled()
  })

  it('scoped set of a program whose snapshot is empty scopes to nothing', () => {
    auth.user = user(7)
    auth.programs = [program(8, [])]
    lens.select(8)
    expect(lens.scopedProjectIds).toEqual(new Set())
    expect(lens.inScope(11)).toBe(false)
  })
})

describe('auth store — program snapshot mapping on /users/me', () => {
  it('loadMe maps me.programs verbatim and tolerates its absence', async () => {
    const getMe = authApi.getMe as unknown as MockInstance
    getMe.mockResolvedValue({
      user: user(7),
      projects: [],
      programs: [program(5, [11, 22])],
    })
    await auth.loadMe()
    expect(auth.programs).toEqual([program(5, [11, 22])])
    getMe.mockResolvedValue({ user: user(7), projects: [] })
    await auth.loadMe()
    expect(auth.programs).toEqual([])
  })

  it('logout empties programs', async () => {
    const getMe = authApi.getMe as unknown as MockInstance
    getMe.mockResolvedValue({ user: user(7), projects: [], programs: [program(5, [11])] })
    await auth.loadMe()
    expect(auth.programs.length).toBe(1)
    await auth.logout()
    expect(auth.programs).toEqual([])
  })
})
