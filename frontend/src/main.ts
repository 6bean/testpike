import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'

import App from './App.vue'
import router from './router'
import TimeCell from './components/TimeCell.vue'
import EmptyState from './components/EmptyState.vue'
import TableSkeleton from './components/TableSkeleton.vue'
import RowActions from './components/RowActions.vue'
import DataTablePage from './components/DataTablePage.vue'
import i18n from './utils/i18n'
import { permission } from './directives/permission'
import { installTableViewportHScrollbar } from './utils/tableViewportHScrollbar'
import { installDialogDragDefault } from './utils/dialogDragDefault'
import { installTableFocusReveal } from './utils/tableFocusReveal'
import { installTableLabelFocusGuard, installTableBodyClickJumpGuard } from './utils/tableLabelFocusGuard'
import './styles/theme.scss'

const app = createApp(App)

for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

app.directive('permission', permission)

app.component('TimeCell', TimeCell)
app.component('EmptyState', EmptyState)
app.component('TableSkeleton', TableSkeleton)
app.component('RowActions', RowActions)
app.component('DataTablePage', DataTablePage)

app.use(createPinia())
app.use(router)
app.use(i18n)
app.use(ElementPlus)

// Dialog dragging must work on every dialog (report 2026-11-23): the default
// flips once here through Element Plus's global-config channel; pages add no
// per-page draggable flags and no hand-rolled header listeners (S-18 gate).
// Runs before mount — dialogs resolve the config at render time.
installDialogDragDefault(app)

app.mount('#app')

installTableViewportHScrollbar()
installTableFocusReveal()
installTableLabelFocusGuard()
// Plain-cell clicks hand focus to the EP tbody (tabindex=-1); on a table wider
// than its scroll port the browser reveals the whole body and the horizontal
// scrollbar snaps to the right (requirements-page report 2026-11-22). Site-wide
// single source — pages must not add local scroll-restore patches.
installTableBodyClickJumpGuard()
