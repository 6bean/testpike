import type { MockSpaceItem } from '@/api/mock'

/**
 * The one place a mock space's display name is composed.
 *
 * Spaces carry a free-text alias that users recognise faster than the code, so
 * every surface that names a space shows the name with the alias appended in
 * full-width parentheses. That rule used to be
 * re-typed in each mock view and the contract-import wizard read a bare name
 * passed down from its host page instead — one object, two spellings on screen.
 * Point every consumer here; a second builder in a page is a defect.
 */
export function mockSpaceLabel(space: Pick<MockSpaceItem, 'name' | 'alias'> | null | undefined): string {
  if (!space) return ''
  const alias = space.alias ? `\uff08${space.alias}\uff09` : ''
  return `${space.name}${alias}`
}
