/**
 * Single-source vitest mock factories for the auth API surface (R40 —
 * extract-common law: the moment a second spec needs the same stubbed
 * getMe/login/logout/updateMyProfile + rbac myPermissions doors, the
 * assembly lives here and nowhere else).
 *
 * Consumers wire them through async vi.mock factories so the dynamic
 * import resolves after hoisting (vitest hoists vi.mock above the static
 * imports; referencing an imported identifier directly inside the factory
 * body would hit a TDZ crash at collection time — see the lensWiringHarness
 * precedent in tests/comp).
 */
import { vi } from 'vitest'

export function authApiMock() {
  return {
    getMe: vi.fn(),
    login: vi.fn(),
    logout: vi.fn(async () => undefined),
    updateMyProfile: vi.fn(),
  }
}

export function rbacApiMock() {
  return {
    myPermissions: vi.fn(async () => []),
  }
}
