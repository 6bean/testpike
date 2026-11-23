import { computed, reactive, watch } from 'vue'



export interface ColumnDef {
  
  key: string
  
  labelKey: string
  
  width: number | null
}

export interface ColumnState {
  key: string
  visible: boolean
  width: number | null
  
  fixed: 'left' | null
}

export type TableDensity = 'default' | 'small'

interface StoredConfig {
  columns: ColumnState[]
  density: TableDensity
}

const STORAGE_PREFIX = 'tp-colcfg-'

function load(viewKey: string): StoredConfig | null {
  try {
    const raw = localStorage.getItem(STORAGE_PREFIX + viewKey)
    return raw ? (JSON.parse(raw) as StoredConfig) : null
  } catch {
    return null
  }
}


export function useColumnConfig(viewKey: string, defs: ColumnDef[]) {
  const saved = load(viewKey)
  const savedMap = new Map((saved?.columns ?? []).map((c) => [c.key, c]))
  const merged: ColumnState[] = []
  const orderedKeys: string[] = []
  for (const c of savedMap.keys()) {
    if (defs.some((d) => d.key === c)) orderedKeys.push(c)
  }
  defs.forEach((d, i) => {
    if (!savedMap.has(d.key)) orderedKeys.splice(Math.min(i, orderedKeys.length), 0, d.key)
  })
  for (const k of orderedKeys) {
    const def = defs.find((d) => d.key === k)!
    const st = savedMap.get(k)
    merged.push({
      key: k,
      visible: st ? st.visible : true,
      width: st ? st.width : def.width,
      fixed: st?.fixed ?? null,
    })
  }

  const state = reactive<{ columns: ColumnState[]; density: TableDensity }>({
    columns: merged,
    density: (saved?.density as TableDensity) ?? 'default',
  })

  watch(
    state,
    () => {
      try {
        localStorage.setItem(STORAGE_PREFIX + viewKey, JSON.stringify(state satisfies StoredConfig))
      } catch {
      }
    },
    { deep: true },
  )

  const defOf = (key: string): ColumnDef => defs.find((d) => d.key === key)!

  
  const visibleColumns = computed(() =>
    state.columns
      .filter((c) => c.visible)
      .map((c) => ({ ...c, labelKey: defOf(c.key).labelKey })),
  )

  function hide(key: string): void {
    const c = state.columns.find((x) => x.key === key)
    if (c) c.visible = false
  }

  function show(key: string): void {
    const c = state.columns.find((x) => x.key === key)
    if (c) c.visible = true
  }

  function toggle(key: string): void {
    const c = state.columns.find((x) => x.key === key)
    if (c) c.visible = !c.visible
  }

  
  function move(key: string, top: boolean): void {
    const cols = state.columns
    const i = cols.findIndex((c) => c.key === key)
    const j = top ? i - 1 : i + 1
    if (i < 0 || j < 0 || j >= cols.length) return
    const [c] = cols.splice(i, 1)
    cols.splice(j, 0, c)
  }

  
  function updateWidth(key: string, width: number): void {
    const c = state.columns.find((x) => x.key === key)
    if (c) c.width = Math.max(60, Math.round(width))
  }

  function setDensity(d: TableDensity): void {
    state.density = d
  }

  
  function reset(): void {
    state.columns = defs.map((d) => ({ key: d.key, visible: true, width: d.width, fixed: null }))
    state.density = 'default'
  }

  
  function toggleFixed(key: string): void {
    const c = state.columns.find((x) => x.key === key)
    if (c) c.fixed = c.fixed === 'left' ? null : 'left'
  }

  return { state, visibleColumns, hide, show, toggle, move, updateWidth, setDensity, reset, toggleFixed }
}
