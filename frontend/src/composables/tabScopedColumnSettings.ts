import { computed, reactive, type ComputedRef, type Ref } from 'vue'
import { useColumnConfig, type ColumnDef, type TableDensity } from './useColumnConfig'

/**
 * Tab-scoped view-state settings host (TB group R25, mode 1 = scope-aware
 * single piece, Ant ProTable options parity).
 *
 * Problem this solves: one ColumnSettings/density popover parked outside an
 * el-tabs block bound to a single useColumnConfig viewKey silently acts on
 * whichever table happens to consume it -- when the user switches tabs the
 * visible table stops reacting (container scope != effect scope).
 *
 * Shape: the page declares one settingsFor(tabKey, viewKey, defs|null) pane
 * per el-tab-pane; the popover rows/density/disabled state route through
 * currentPane, which follows the active tab ref. Each pane owns its own
 * useColumnConfig instance (per-pane localStorage key, zero cross-tab
 * pollution); this layer adds only pane routing + honest-disable metadata --
 * the merge math stays single-sourced in useColumnConfig (no second
 * implementation, composition not inheritance).
 *
 * Fixed-view panes (defs=null: a table whose columns are not user-configurable)
 * expose settingsDisabled/densityDisabled + a named i18n reason so the shared
 * piece renders disabled controls with a pointing tooltip instead of silently
 * doing nothing (R25: no invisible no-op).
 */

/** Popover row shape (column state + i18n labelKey), shared with ColumnSettings. */
export interface PaneSettingRow {
  key: string
  visible: boolean
  width: number | null
  fixed: 'left' | null
  labelKey: string
}

/** One pane's scoped settings view (a useColumnConfig surface plus metadata). */
export interface TabPaneSettings {
  /** The el-tab-pane name this view is bound to. */
  tabKey: string
  /** localStorage viewKey suffix (full key = tp-colcfg-{viewKey}). */
  viewKey: string
  /** False for fixed-view panes (no column configuration, honest absence). */
  configurable: boolean
  /**
   * Column-config object handed to DataTablePage :column-config (drag-width
   * write-back). Null for fixed panes so a table cannot be wired by accident.
   */
  cfg: ReturnType<typeof useColumnConfig> | null
  /** Reactive state (columns + density) -- drives the table bindings. */
  state: ReturnType<typeof useColumnConfig>['state']
  /** Visible columns for the pane's v-for (empty array on fixed panes). */
  visibleColumns: ReturnType<typeof useColumnConfig>['visibleColumns']
  /** Popover rows for the pane's column set (empty array on fixed panes). */
  settingsRows: ComputedRef<PaneSettingRow[]>
  /** Column-settings button disabled state (true on fixed / unknown panes). */
  settingsDisabled: ComputedRef<boolean>
  /** Density switches disabled state (same condition as settingsDisabled). */
  densityDisabled: ComputedRef<boolean>
  /** i18n key naming WHY the pane is fixed (tooltip text source; '' otherwise). */
  disabledReason: ComputedRef<string>
  toggle: (key: string) => void
  move: (key: string, top: boolean) => void
  setDensity: (d: TableDensity) => void
  reset: () => void
  toggleFixed: (key: string) => void
}

/** Pane declaration input (what a page hands settingsFor). */
export interface PaneOptions {
  /** i18n key the tooltip shows when the pane is a fixed view (required when defs=null). */
  disabledReasonKey?: string
}

export function useTabScopedSettings(activeTab: Ref<string>) {
  const panes = new Map<string, TabPaneSettings>()
  const viewKeys = new Set<string>()

  function buildPane(tabKey: string, viewKey: string, defs: ColumnDef[] | null, opts: PaneOptions): TabPaneSettings {
    const fixed = defs === null
    const cfg = fixed ? null : useColumnConfig(viewKey, defs)
    const settingsRows = computed<PaneSettingRow[]>(() =>
      cfg
        ? cfg.state.columns.map((c) => ({ ...c, labelKey: defs!.find((d) => d.key === c.key)!.labelKey }))
        : [],
    )
    const disabled = computed(() => fixed)
    const reason = computed(() => (fixed ? opts.disabledReasonKey ?? '' : ''))
    return {
      tabKey,
      viewKey,
      configurable: !fixed,
      cfg,
      state: cfg ? cfg.state : reactive({ columns: [], density: 'default' as const }),
      visibleColumns: cfg ? cfg.visibleColumns : computed(() => []),
      settingsRows,
      settingsDisabled: disabled,
      densityDisabled: disabled,
      disabledReason: reason,
      toggle: (key: string) => cfg?.toggle(key),
      move: (key: string, top: boolean) => cfg?.move(key, top),
      setDensity: (d: TableDensity) => cfg?.setDensity(d),
      reset: () => cfg?.reset(),
      toggleFixed: (key: string) => cfg?.toggleFixed(key),
    }
  }

  /** Pane exposed to the popover when activeTab matches no declaration (inert). */
  const inertPane = buildPane('__inert__', '__inert__', null, {})

  /**
   * Declare one pane's scoped settings view. tabKey must match an el-tab-pane
   * name; viewKey is the per-pane storage key suffix (tp-colcfg-{viewKey}) --
   * reusing a viewKey across panes is the multi-table-shared-key defect, so it
   * fails fast in dev instead of silently cross-writing localStorage.
   */
  function settingsFor(tabKey: string, viewKey: string, defs: ColumnDef[] | null, opts: PaneOptions = {}): TabPaneSettings {
    if (panes.has(tabKey)) {
      throw new Error(`tab-scoped settings: duplicate tabKey '${tabKey}' (one declaration per el-tab-pane)`)
    }
    if (viewKeys.has(viewKey)) {
      throw new Error(`tab-scoped settings: duplicate viewKey '${viewKey}' (each pane needs its own storage key)`)
    }
    if (defs === null && !opts.disabledReasonKey) {
      throw new Error(`tab-scoped settings: fixed pane '${tabKey}' must name a disabledReasonKey (no invisible no-op)`)
    }
    viewKeys.add(viewKey)
    const pane = buildPane(tabKey, viewKey, defs, opts)
    panes.set(tabKey, pane)
    return pane
  }

  /** The pane the shared popover is currently scoped to (unknown tab = inert). */
  const currentPane = computed(() => panes.get(activeTab.value) ?? inertPane)

  return { settingsFor, currentPane }
}
