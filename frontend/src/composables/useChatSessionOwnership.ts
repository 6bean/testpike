/**
 * Conversation-ownership boundary (BE batch , design option A
 * §two.4). Pure helper, no reactive state: answers one honest question —
 * does the open conversation belong to a project segment other than the one
 * the user is standing on right now?
 *
 * Null means "unknown or no segment": no boundary is drawn then, because a
 * claim about ownership needs both sides to exist. The panel keeps the
 * owning segment in memory next to the session pointer; reload re-reads the
 * per-slice pointer, so a restored thread always names its own segment.
 */

/** True only when both slices exist and they differ. */
export function ownsOtherSlice(
  ownerProjectId: number | null,
  currentProjectId: number | null,
): boolean {
  if (ownerProjectId == null || currentProjectId == null) return false
  return ownerProjectId !== currentProjectId
}
