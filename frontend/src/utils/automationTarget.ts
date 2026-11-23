
export const MOBILE_FAMILY_TARGETS: readonly string[] = ['app', 'mp', 'harmony']


export function isMobileFamilyTarget(target?: string | null): boolean {
  return !!target && MOBILE_FAMILY_TARGETS.includes(target)
}


export function wantedPlatformsForTarget(target?: string | null): string[] {
  if (target === 'mp') return ['MP']
  if (target === 'harmony') return ['HARMONY_NEXT']
  return ['ANDROID', 'IOS']
}
