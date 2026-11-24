/**
 * Site-wide dialog dragging default (user report 2026-11-23: dialogs such as
 * the site-profile create form could not be dragged; only 120 of the 146
 * `el-dialog` usages carried a per-page `draggable` flag).
 *
 * Mechanism: Element Plus 2.9 resolves each dialog's drag state as
 * `props.draggable ?? globalConfig.dialog.draggable ?? false` (see
 * element-plus/es/components/dialog/src/use-dialog.mjs). Feeding
 * `dialog.draggable: true` through the plugin's own global-config channel
 * (`app.use(ElementPlus, config)`) therefore makes dragging the site-wide
 * default without touching the component's internal prop definitions.
 * Per-page values still win: an explicit `:draggable="false"` stays false,
 * an explicit `draggable` stays true.
 *
 * Single source: this is the only place that declares the dialog dragging
 * default. Pages must NOT add hand-rolled header drag listeners (guarded by
 * the S-12 criterion in tools/ui_structure_audit.py) and must not duplicate
 * this config elsewhere (contract: tests/ui/dialogDragDefault.test.ts).
 */
import type { App } from 'vue'
import { provideGlobalConfig } from 'element-plus'

/** Global config fragment carrying the dialog dragging default. */
export const dialogDragGlobalConfig = {
  dialog: {
    draggable: true,
  },
}

/**
 * Install the dragging default onto the app through Element Plus's own
 * global-config provider. main.ts may alternatively pass the same fragment
 * to `app.use(ElementPlus, ...)`, which reaches the identical provider.
 * Kept as a function so the wiring point stays greppable from one file.
 */
export function installDialogDragDefault(app: App): void {
  provideGlobalConfig(dialogDragGlobalConfig, app, true)
}
