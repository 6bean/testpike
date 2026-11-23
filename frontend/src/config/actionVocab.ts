
import { isMobileFamilyTarget } from '@/utils/automationTarget'

export type VocabFieldType =
  | 'text'
  | 'number'
  | 'bool'
  | 'locator'
  | 'stringList'
  | 'steps'
  | 'condition'
  | 'json'
  | 'enum'
  | 'code'

export interface VocabField {
  
  key: string
  
  label: string
  type: VocabFieldType
  required?: boolean
  
  enum?: string[]
  
  min?: number
  
  max?: number
  
  preserveEmptyList?: boolean
  
  hint?: string
}

export interface ActionSpec {
  action: string
  
  label: string
  
  contract: string
  
  container?: boolean
 
  manualOnly?: boolean
  fields: VocabField[]
}

const TARGET: VocabField = { key: 'target', label: 'vocab.field.target', type: 'locator', hint: 'vocab.hint.target_locator' }
const VALUE: VocabField = { key: 'value', label: 'vocab.field.value', type: 'text', hint: 'vocab.hint.interp_value' }

const APP_TARGET: VocabField = { key: 'target', label: 'vocab.field.target', type: 'locator', hint: 'vocab.hint.app_target_locator' }

const APP_WEBVIEW: VocabField = { key: 'webview', label: 'vocab.field.app_webview', type: 'bool', hint: 'vocab.hint.app_webview' }

export const ACTION_VOCAB: ActionSpec[] = [
  { action: 'goto', label: 'vocab.action.goto', contract: '04 §2.1', fields: [
    { key: 'url', label: 'vocab.field.url', type: 'text', required: true, hint: 'vocab.hint.env_url' },
  ] },
  { action: 'click', label: 'vocab.action.click', contract: '04 §2.1', fields: [TARGET] },
  { action: 'input', label: 'vocab.action.input', contract: '04 §2.1', fields: [TARGET, VALUE] },
  { action: 'select', label: 'vocab.action.select', contract: '04 §2.1', fields: [
    TARGET,
    { key: 'value', label: 'vocab.field.value_select', type: 'text' },
    { key: 'option', label: 'vocab.field.option', type: 'text', hint: 'vocab.hint.value_or_option' },
  ] },
  { action: 'expect', label: 'vocab.action.expect', contract: '04 §2.1 / §2.1.1', fields: [
    TARGET,
    { key: 'text', label: 'vocab.field.text', type: 'text', hint: 'vocab.hint.target_or_text' },
    { key: 'exact', label: 'vocab.field.exact', type: 'bool', hint: 'vocab.hint.legacy_exact' },
    { key: 'timeoutMs', label: 'vocab.field.timeoutMs', type: 'number' },
    { key: 'state', label: 'vocab.field.state', type: 'enum', enum: ['visible', 'attached', 'detached', 'hidden'], hint: 'vocab.hint.expect_state' },
    { key: 'matchType', label: 'vocab.field.matchType', type: 'enum', enum: ['contains', 'exact', 'regex', 'range'], hint: 'vocab.hint.match_type' },
    { key: 'pattern', label: 'vocab.field.pattern', type: 'text', hint: 'vocab.hint.pattern' },
    { key: 'min', label: 'vocab.field.min', type: 'number', hint: 'vocab.hint.range_end' },
    { key: 'max', label: 'vocab.field.max', type: 'number' },
    { key: 'bounds', label: 'vocab.field.bounds', type: 'enum', enum: ['closed', 'open', 'left-open', 'right-open'], hint: 'vocab.hint.bounds' },
    { key: 'strip', label: 'vocab.field.strip', type: 'text', hint: 'vocab.hint.strip' },
  ] },
  { action: 'wait', label: 'vocab.action.wait', contract: '04 §2.1', fields: [
    { key: 'ms', label: 'vocab.field.ms', type: 'number' },
    TARGET,
    { key: 'state', label: 'vocab.field.state', type: 'enum', enum: ['visible', 'attached', 'detached', 'hidden'], hint: 'vocab.hint.wait_state' },
  ] },
  { action: 'screenshot', label: 'vocab.action.screenshot', contract: '04 §2.1', fields: [
    { key: 'name', label: 'vocab.field.name', type: 'text', hint: 'vocab.hint.baseline_source' },
  ] },
  { action: 'loop', label: 'vocab.action.loop', contract: '04 §2.1', container: true, fields: [
    { key: 'items', label: 'vocab.field.items', type: 'stringList', preserveEmptyList: true, hint: 'vocab.hint.loop_items' },
    { key: 'count', label: 'vocab.field.count', type: 'number', hint: 'vocab.hint.loop_count' },
    { key: 'var', label: 'vocab.field.var', type: 'text', hint: 'vocab.hint.loop_var' },
    { key: 'steps', label: 'vocab.field.steps', type: 'steps', required: true },
  ] },
  { action: 'if', label: 'vocab.action.if', contract: '04 §2.1', container: true, fields: [
    { key: 'condition', label: 'vocab.field.condition', type: 'condition', required: true, hint: 'vocab.hint.condition_grammar' },
    { key: 'steps', label: 'vocab.field.steps_then', type: 'steps', required: true },
    { key: 'elseSteps', label: 'vocab.field.elseSteps', type: 'steps', hint: 'vocab.hint.else_optional' },
  ] },
  { action: 'code.step', label: 'vocab.action.code_step', contract: '04 §2.1', manualOnly: true, fields: [
    { key: 'language', label: 'vocab.field.language', type: 'enum', enum: ['python'], required: true, hint: 'vocab.hint.language_python_only' },
    { key: 'code', label: 'vocab.field.code', type: 'code', required: true, hint: 'vocab.hint.sandbox_code' },
    { key: 'timeoutMs', label: 'vocab.field.timeoutMs', type: 'number', min: 1, max: 30000, hint: 'vocab.hint.timeout_ms_domain' },
    { key: 'saveAs', label: 'vocab.field.saveAs', type: 'text', hint: 'vocab.hint.code_save_as' },
  ] },
  { action: 'visual.assert', label: 'vocab.action.visual_assert', contract: '04 §2.1', fields: [
    { key: 'baselineKey', label: 'vocab.field.baselineKey', type: 'text' },
    { key: 'tolerance', label: 'vocab.field.tolerance', type: 'number' },
  ] },
  { action: 'console.assert', label: 'vocab.action.console_assert', contract: '04 §2.1.2', fields: [
    { key: 'level', label: 'vocab.field.level', type: 'stringList', hint: 'vocab.hint.level_default' },
    { key: 'ignorePatterns', label: 'vocab.field.ignorePatterns', type: 'stringList' },
    { key: 'mode', label: 'vocab.field.mode', type: 'enum', enum: ['fail', 'warn', 'collect'] },
    { key: 'scope', label: 'vocab.field.scope', type: 'enum', enum: ['sinceLast', 'full'], hint: 'vocab.hint.scope_default' },
  ] },
  { action: 'net.assert', label: 'vocab.action.net_assert', contract: '04 §2.1.2', fields: [
    { key: 'match', label: 'vocab.field.match', type: 'json' },
    { key: 'expect', label: 'vocab.field.expect_net', type: 'json', hint: 'vocab.hint.jsonpath_subset' },
    { key: 'absent', label: 'vocab.field.absent', type: 'bool', hint: 'vocab.hint.neg_first_class' },
    { key: 'window', label: 'vocab.field.window', type: 'json' },
  ] },
  { action: 'http.request', label: 'vocab.action.http_request', contract: '04 §2.1.2', fields: [
    { key: 'method', label: 'vocab.field.method', type: 'enum', enum: ['GET', 'POST', 'PUT', 'DELETE', 'PATCH'], required: true },
    { key: 'url', label: 'vocab.field.url', type: 'text', required: true, hint: 'vocab.hint.url_guard' },
    { key: 'headers', label: 'vocab.field.headers', type: 'json' },
    { key: 'body', label: 'vocab.field.body', type: 'json' },
    { key: 'expect', label: 'vocab.field.expect_http', type: 'json', hint: 'vocab.hint.pure_call' },
    { key: 'poll', label: 'vocab.field.poll', type: 'json', hint: 'vocab.hint.poll_keys' },
    { key: 'timeoutMs', label: 'vocab.field.timeoutMs', type: 'number' },
    { key: 'saveAs', label: 'vocab.field.saveAs', type: 'text', hint: 'vocab.hint.save_as_var' },
  ] },
  { action: 'sql.assert', label: 'vocab.action.sql_assert', contract: '04 §2.1.2', fields: [
    { key: 'conn', label: 'vocab.field.conn', type: 'text', required: true, hint: 'vocab.hint.conn_env' },
    { key: 'sql', label: 'vocab.field.sql', type: 'text', required: true, hint: 'vocab.hint.sql_readonly' },
    { key: 'params', label: 'vocab.field.params', type: 'json' },
    { key: 'expect', label: 'vocab.field.expect', type: 'json', required: true, hint: 'vocab.hint.sql_expect' },
    { key: 'retry', label: 'vocab.field.retry', type: 'json' },
  ] },
  { action: 'sql.exec', label: 'vocab.action.sql_exec', contract: '04 §2.1.2', manualOnly: true, fields: [
    { key: 'conn', label: 'vocab.field.conn', type: 'text', required: true },
    { key: 'sql', label: 'vocab.field.sql', type: 'text', required: true, hint: 'vocab.hint.dml_rules' },
    { key: 'params', label: 'vocab.field.params', type: 'json' },
  ] },
  { action: 'sql.query', label: 'vocab.action.sql_query', contract: '04 §2.1.2 v2.22', fields: [
    { key: 'conn', label: 'vocab.field.conn', type: 'text', required: true },
    { key: 'sql', label: 'vocab.field.sql', type: 'text', required: true, hint: 'vocab.hint.query_lex' },
    { key: 'params', label: 'vocab.field.params', type: 'json' },
    { key: 'saveAs', label: 'vocab.field.saveAs', type: 'text', required: true, hint: 'vocab.hint.save_as_expand' },
  ] },
  { action: 'app.launch', label: 'vocab.action.app_launch', contract: '04 §2.2 app.* IR', fields: [
    { key: 'pkg', label: 'vocab.field.app_pkg', type: 'text', hint: 'vocab.hint.app_pkg' },
    { key: 'page', label: 'vocab.field.app_page', type: 'text', hint: 'vocab.hint.app_page' },
  ] },
  { action: 'app.close', label: 'vocab.action.app_close', contract: '04 §2.2 app.* IR', fields: [
    { key: 'pkg', label: 'vocab.field.app_pkg', type: 'text', hint: 'vocab.hint.app_pkg_optional' },
  ] },
  { action: 'app.tap', label: 'vocab.action.app_tap', contract: '04 §2.2 app.* IR', fields: [APP_TARGET, APP_WEBVIEW] },
  { action: 'app.longPress', label: 'vocab.action.app_longPress', contract: '04 §2.2 app.* IR', fields: [
    APP_TARGET,
    { key: 'duration', label: 'vocab.field.app_duration', type: 'number', hint: 'vocab.hint.app_duration' },
    APP_WEBVIEW,
  ] },
  { action: 'app.doubleTap', label: 'vocab.action.app_doubleTap', contract: '04 §2.2 app.* IR', fields: [APP_TARGET, APP_WEBVIEW] },
  { action: 'app.input', label: 'vocab.action.app_input', contract: '04 §2.2 app.* IR', fields: [
    APP_TARGET,
    { key: 'text', label: 'vocab.field.app_text_input', type: 'text', hint: 'vocab.hint.app_text_input' },
    APP_WEBVIEW,
  ] },
  { action: 'app.clearInput', label: 'vocab.action.app_clearInput', contract: '04 §2.2 app.* IR', fields: [APP_TARGET, APP_WEBVIEW] },
  { action: 'app.swipe', label: 'vocab.action.app_swipe', contract: '04 §2.2 app.* IR', fields: [
    { key: 'direction', label: 'vocab.field.app_direction', type: 'enum', enum: ['up', 'down', 'left', 'right'], hint: 'vocab.hint.app_direction' },
  ] },
  { action: 'app.scroll', label: 'vocab.action.app_scroll', contract: '04 §2.2 app.* IR', fields: [
    APP_TARGET,
    { key: 'direction', label: 'vocab.field.app_direction', type: 'enum', enum: ['up', 'down', 'left', 'right'], hint: 'vocab.hint.app_direction' },
    APP_WEBVIEW,
  ] },
  { action: 'app.back', label: 'vocab.action.app_back', contract: '04 §2.2 app.* IR', fields: [] },
  { action: 'app.screenshot', label: 'vocab.action.app_screenshot', contract: '04 §2.2 app.* IR', fields: [] },
  { action: 'app.assertVisible', label: 'vocab.action.app_assertVisible', contract: '04 §2.2 app.* IR', fields: [APP_TARGET, APP_WEBVIEW] },
  { action: 'app.assertNotVisible', label: 'vocab.action.app_assertNotVisible', contract: '04 §2.2 app.* IR', fields: [APP_TARGET, APP_WEBVIEW] },
  { action: 'app.assertText', label: 'vocab.action.app_assertText', contract: '04 §2.2 app.* IR', fields: [
    APP_TARGET,
    { key: 'text', label: 'vocab.field.text', type: 'text', hint: 'vocab.hint.app_expect_text' },
    { key: 'mode', label: 'vocab.field.mode', type: 'enum', enum: ['contains', 'equals', 'regex'], hint: 'vocab.hint.app_text_mode' },
    APP_WEBVIEW,
  ] },
  { action: 'app.waitVisible', label: 'vocab.action.app_waitVisible', contract: '04 §2.2 app.* IR', fields: [
    APP_TARGET,
    { key: 'timeoutMs', label: 'vocab.field.timeoutMs', type: 'number', hint: 'vocab.hint.app_wait_timeout' },
    APP_WEBVIEW,
  ] },
  { action: 'app.extractText', label: 'vocab.action.app_extractText', contract: '04 §2.2 app.* IR', fields: [
    APP_TARGET,
    { key: 'saveAs', label: 'vocab.field.saveAs', type: 'text', hint: 'vocab.hint.app_save_as' },
    APP_WEBVIEW,
  ] },
  { action: 'app.deepLink', label: 'vocab.action.app_deepLink', contract: '04 §2.2 app.* IR', fields: [
    { key: 'url', label: 'vocab.field.app_deeplink_url', type: 'text', required: true, hint: 'vocab.hint.app_deeplink_url' },
  ] },
  { action: 'app.openUrl', label: 'vocab.action.app_openUrl', contract: '04 §2.2 app.* IR', fields: [
    { key: 'url', label: 'vocab.field.url', type: 'text', required: true, hint: 'vocab.hint.app_open_url' },
  ] },
  { action: 'app.rotation', label: 'vocab.action.app_rotation', contract: '04 §2.2 app.* IR', fields: [
    { key: 'orientation', label: 'vocab.field.app_orientation', type: 'enum', enum: ['portrait', 'landscape'], required: true, hint: 'vocab.hint.app_orientation' },
  ] },
  { action: 'app.home', label: 'vocab.action.app_home', contract: '04 §2.2 app.* IR', fields: [] },
  { action: 'app.background', label: 'vocab.action.app_background', contract: '04 §2.2 app.* IR', fields: [
    { key: 'durationSec', label: 'vocab.field.app_duration_sec', type: 'number', min: 1, max: 3600, hint: 'vocab.hint.app_duration_sec' },
  ] },
  { action: 'app.foreground', label: 'vocab.action.app_foreground', contract: '04 §2.2 app.* IR', fields: [] },
  { action: 'app.clearData', label: 'vocab.action.app_clearData', contract: '04 §2.2 app.* IR', fields: [
    { key: 'pkg', label: 'vocab.field.app_pkg', type: 'text', hint: 'vocab.hint.app_pkg' },
  ] },
  { action: 'app.grantPermission', label: 'vocab.action.app_grantPermission', contract: '04 §2.2 app.* IR', fields: [
    { key: 'pkg', label: 'vocab.field.app_pkg', type: 'text', hint: 'vocab.hint.app_pkg' },
    { key: 'permissions', label: 'vocab.field.app_permissions', type: 'stringList', required: true, hint: 'vocab.hint.app_permissions' },
  ] },
  { action: 'app.wakeUnlock', label: 'vocab.action.app_wakeUnlock', contract: '04 §2.2 app.* IR', fields: [] },
  { action: 'app.networkProfile', label: 'vocab.action.app_networkProfile', contract: '04 §2.2 app.* IR', fields: [
    { key: 'profile', label: 'vocab.field.app_net_profile', type: 'enum', enum: ['wifi', 'slow2g', 'slow3g', 'offline', 'custom'], required: true, hint: 'vocab.hint.app_net_profile' },
    { key: 'custom', label: 'vocab.field.app_net_custom', type: 'json', hint: 'vocab.hint.app_net_custom' },
  ] },
  { action: 'app.install', label: 'vocab.action.app_install', contract: '04 §2.2 app.* IR', fields: [
    { key: 'pkg', label: 'vocab.field.app_pkg', type: 'text', required: true, hint: 'vocab.hint.app_pkg' },
    { key: 'artifactRef', label: 'vocab.field.app_artifact_ref', type: 'text', hint: 'vocab.hint.app_artifact_ref' },
  ] },
  { action: 'app.uninstall', label: 'vocab.action.app_uninstall', contract: '04 §2.2 app.* IR', fields: [
    { key: 'pkg', label: 'vocab.field.app_pkg', type: 'text', required: true, hint: 'vocab.hint.app_pkg' },
  ] },
  { action: 'app.verifyVersion', label: 'vocab.action.app_verifyVersion', contract: '04 §2.2 app.* IR', fields: [
    { key: 'pkg', label: 'vocab.field.app_pkg', type: 'text', required: true, hint: 'vocab.hint.app_pkg' },
    { key: 'expectVersion', label: 'vocab.field.app_expect_version', type: 'text', required: true, hint: 'vocab.hint.app_expect_version' },
  ] },
  { action: 'app.perf', label: 'vocab.action.app_perf', contract: '04 §2.2 app.* IR', fields: [
    { key: 'expr', label: 'vocab.field.app_perf_expr', type: 'text', required: true, hint: 'vocab.hint.app_perf_expr' },
  ] },
  { action: 'app.launchPerf', label: 'vocab.action.app_launchPerf', contract: '04 §2.2 app.* IR', fields: [
    { key: 'mode', label: 'vocab.field.app_launch_mode', type: 'enum', enum: ['cold', 'hot'], hint: 'vocab.hint.app_launch_mode' },
  ] },
]


export function actionSpecOf(action: string): ActionSpec | undefined {
  return ACTION_VOCAB.find((s) => s.action === action)
}


export function containerBranches(action: string): string[] {
  const spec = actionSpecOf(action)
  if (!spec?.container) return []
  return spec.fields.filter((f) => f.type === 'steps').map((f) => f.key)
}


export const APPAUTO_IR_NAMESPACE = 'app.'


export const APPAUTO_CAPABILITY_FLAGS: readonly string[] = [
  'webview-hybrid', 'network-throttle', 'record-native', 'flutter', 'image-matching',
]


export function vocabForTarget(automationTarget?: string): ActionSpec[] {
  const withApp = isMobileFamilyTarget(automationTarget)
  if (withApp) return ACTION_VOCAB
  return ACTION_VOCAB.filter((s) => !s.action.startsWith(APPAUTO_IR_NAMESPACE))
}
