
export type VerdictTagType = 'success' | 'warning' | 'danger' | 'info'


export function verdictTagType(state: string | null | undefined): VerdictTagType {
  if (state === 'GREEN') return 'success'
  if (state === 'YELLOW') return 'warning'
  if (state === 'RED') return 'danger'
  return 'info'
}


export function supportStateLabel(state: string, translate: (key: string) => string): string {
  const map: Record<string, string> = {
    GREEN: 'upgrade.stateGreen',
    YELLOW: 'upgrade.stateYellow',
    RED: 'upgrade.stateRed',
    UNKNOWN: 'upgrade.stateUnknown',
  }
  const key = map[state]
  return key ? translate(key) : state
}
