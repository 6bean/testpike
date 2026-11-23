import { request } from './request'

/**
 * Program (project set) lens API — full mirror of the admin /api/v1/programs
 * bridge (13 endpoints over the V340 three-table single source:
 * tp_program / tp_program_project / tp_user_program).
 *
 * Read paths were first consumed by the contract-subscription panel; write
 * paths back the program console page (project set management). Permission
 * keys currently reuse the project VIEW family on the server side — the
 * dedicated perm:program:* rows land with the wave closing ledger.
 */
export interface ProgramView {
  id: number
  name: string
  description: string | null
  /** 1 = active, 0 = archived (core ProgramView.state) */
  state: number
  createdAt: string
}

export interface ProgramMemberView {
  userId: number
  /** Membership role rank within the program lens (core role enum). */
  role: number
}

/** Every program (both states); callers filter by state per user intent. */
export function listPrograms(): Promise<ProgramView[]> {
  return request({ url: '/programs', method: 'get' })
}

/** One program by id (missing rows surface through the shared error channel). */
export function getProgram(id: number): Promise<ProgramView> {
  return request({ url: `/programs/${id}`, method: 'get' })
}

/** Create a program; returns the new id. */
export function createProgram(name: string, description?: string | null): Promise<number> {
  return request({ url: '/programs', method: 'post', data: { name, description } })
}

/** Replace the editable fields of a program (name + description). */
export function updateProgram(id: number, name: string, description?: string | null): Promise<void> {
  return request({ url: `/programs/${id}`, method: 'put', data: { name, description } })
}

/** Flip lifecycle state: 1 = active, 0 = archived. */
export function setProgramState(id: number, state: number): Promise<void> {
  return request({ url: `/programs/${id}/state`, method: 'put', params: { state } })
}

/** Delete a program lens row (projects themselves are never touched by the lens). */
export function deleteProgram(id: number): Promise<void> {
  return request({ url: `/programs/${id}`, method: 'delete' })
}

/** Project ids attached to one program (membership rows of the lens). */
export function listProgramProjectIds(programId: number): Promise<number[]> {
  return request({ url: `/programs/${programId}/projects`, method: 'get' })
}

/** Attach a project to the program (idempotent on the server). */
export function attachProgramProject(programId: number, projectId: number): Promise<void> {
  return request({ url: `/programs/${programId}/projects/${projectId}`, method: 'post' })
}

/** Detach a project from the program (leaves the project itself intact). */
export function detachProgramProject(programId: number, projectId: number): Promise<void> {
  return request({ url: `/programs/${programId}/projects/${projectId}`, method: 'delete' })
}

/** Members of one program with their lens role ranks. */
export function listProgramMembers(programId: number): Promise<ProgramMemberView[]> {
  return request({ url: `/programs/${programId}/members`, method: 'get' })
}

/** Grant someone membership in the program. */
export function addProgramMember(programId: number, userId: number, role: number): Promise<void> {
  return request({ url: `/programs/${programId}/members`, method: 'post', data: { userId, role } })
}

/** Change a member's lens role. */
export function updateProgramMemberRole(programId: number, userId: number, role: number): Promise<void> {
  return request({ url: `/programs/${programId}/members/${userId}`, method: 'put', data: { role } })
}

/** Revoke someone's program membership. */
export function removeProgramMember(programId: number, userId: number): Promise<void> {
  return request({ url: `/programs/${programId}/members/${userId}`, method: 'delete' })
}
