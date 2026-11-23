
export const menuSynonyms: Record<string, string[]> = {
  '/dashboard': ['仪表盘', '首页', '待办', 'dashboard', 'home'],
  // AN-5: retired leaves' words ride the host paths (no dangling keys —
  // menuSearch gate enforces every synonym key hits a live leaf).
  '/extraction': ['文档', '上传文档', 'prd', 'document', '需求提取', '提取', '需求文档', '源文档'],
  '/requirements': ['需求点', '需求管理', 'requirement', '需求台账', '版本历史'],
  '/ai/generate': ['生成用例', 'ai 生成', '生成任务', 'generate', '批量生成', '场景枚举', '规则对账'],
  // stage 3: the standalone chat page — how users name it in speech.
  '/ai/chat': ['对话', 'ai 对话', '问 ai', 'chat', 'chatbot', '对话坞'],
  // AN-5: regression suites merged into the /cases tab — suite words ride along.
  '/cases': ['用例', '测试用例', 'case', '回归集', '套件', 'suite', '步骤锚', '溯源锚', '需人工精化'],
  '/review': ['评审', '待评审', '审核', 'review'],
  '/knowledge': ['知识', 'mcp 知识', 'knowledge base'],
  '/prompt-templates': ['提示词', '提示词模板', 'prompt'],
  '/skills': ['技能', 'skill', '技能市场', 'skill market'],
  '/tools': ['工具', 'tool', '工具市场', 'tool market'],
  '/extensions': ['插件', '行业插件', 'plugin', 'mcp', '服务', 'mcp server', '扩展', 'extension'],
  '/training-samples': ['样本', '训练数据', 'training sample'],
  '/plans': ['计划', '测试计划', 'plan'],
  '/plans/board': ['看板', '进度', 'board'],
  '/batches': ['批次', '执行批次', 'batch', '定时', 'cron', '调度触发', 'schedule'],
  '/execution': ['试运行', '执行', 'run', '进程', '任务进程', '跨批次'],
  '/api-test': ['接口测试', '接口', 'http', 'api'],
  '/appauto/flow-canvas': ['画布', 'ui flow', '流程图视图', '步骤流程图', 'canvas', 'flow canvas'],
  // AN-4: the inspector leaf retired into the space workbench — its
  // words ride the host path ( precedent: a synonym key must never
  // dangle on a non-leaf path; menuSearch gate enforces it).
  '/mock/spaces': ['流量检视', 'mock', '仿真', '命中', '检视器', '仿真空间', '空间'],
  '/monitor': ['实时监控', '屏幕', 'monitor'],
  '/perf': ['性能', '压力测试', '负载', 'jmeter', 'performance'],
  '/perf/runs': ['压测记录', '压测历史', '运行台账', 'run ledger'],
  '/appauto/devices': ['设备墙', '设备列表', '真机', '手机', 'device'],
  '/appauto/reservations': ['抢设备', '预约设备', '借设备', '设备预约', 'reservation'],
  '/appauto/engines': ['引擎', 'appium', 'mobilewright', '鸿蒙引擎', 'engine'],
  '/appauto/healing': ['自愈', '定位失败修复', 'healing'],
  '/appauto/packages': ['安装包', 'apk', '被测应用', 'app package'],
  '/appauto/elements': ['元素库', '定位元素', '控件档案', 'element'],
  '/web-elements': ['页面元素库', '页面元素账本', '网页元素', '定位锚', '选择器档案', 'web element', '整站测绘', '站点档案', '测绘记录', '进程详情'],
  '/appauto/credentials': ['密码箱', '账号密码', '密钥', '保险箱', 'credential'],
  '/login-states': ['登录态', '登录态账本', '会话', '采集登录态', '已登录', 'token', 'cookie', 'login state'],
  '/appauto/popup-rules': ['弹窗', '广告弹窗', 'popup'],
  '/appauto/samples': ['定位样本', 'ai 样本', 'sample'],
  '/test-report': ['测试报告', '总结报告', 'test report'],
  // AN-3 half B: the report-center leaf merged into /report as the
  // export/snapshot tab; its search words ride the host path so ⌘K still
  // lands on the tab (standalone key removed — dangling-path gate).
  // AN-3 half A: the coverage-matrix leaf merged into /report as a tab;
  // its search words ride the host path so ⌘K still lands on the tab.
  '/report': ['报表', '统计报表', 'reports', '覆盖率', '覆盖矩阵', 'coverage', '报表中心', 'report center'],
  '/program-dashboard': ['项目集大盘', '质量大盘', '集报表', 'program dashboard', 'program-set dashboard'],
  '/defects': ['bug', '缺陷', '问题单', 'defect'],
  '/judge/report': ['评测', '大模型评测', 'judge'],
  '/judge/evalset': ['评测集', '造题', 'QA 集'],
  '/judge/runs': ['评测运行', '发起评测', '跑评测'],
  '/data-factories': ['造数', '数据工厂', '数据池', 'mock 数据', 'factory'],
  // AN-5: /suites retired into the /cases tab — its words ride '/cases'.
  '/tags': ['标签', '打标签', 'tag'],
  '/explore': ['自主探索', '探索测试', 'explore'],
  '/role-bindings': ['角色卡', '绑定', 'role binding'],
  '/projects': ['项目', '工程', 'project'],
  '/programs': ['项目集', '项目分组', '跨项目', 'program', 'program set'],
  '/api-tokens': ['令牌', 'token', '接口令牌', 'api token'],
  '/delete-requests': ['删除审批', '删库申请', 'delete request'],
  '/roles': ['角色权限', '权限点', 'role'],
  '/users': ['用户', '账号', '成员', 'user'],
  '/configs': ['配置', '参数', '设置', 'config'],
  '/llm-configs': ['大模型', '模型配置', 'api key', 'llm'],
  '/audit-logs': ['审计', '操作日志', 'audit'],
  '/mq-dead-letter': ['死信', '消息积压', '重放', 'dead letter', 'dlq'],
  // AN-6: /usage-calibration retired into the /cost calibration tab —
  // its search words ride the host path (dangling-key gate), synonymTabs
  // sends each word to ?tab=calibration.
  // closeout: menu leaf renamed to the cost-center wording (one term
  // per slot against the board tab) — the old leaf wording stays searchable.
  '/cost': ['成本', '成本看板', '花了多少钱', '费用', 'cost', '用量', 'token 校准', '校准', '用量校准', 'usage'],
  '/scheduler-health': ['调度健康', '定时任务健康', 'scheduler'],
  '/execution/pools': ['资源池', '执行器池', 'pool'],
  '/cluster/upgrade': ['升级', '集群升级', 'upgrade'],
}

/**
 * Synonym → host-tab routing : when a ⌘K hit lands
 * on a synonym word inherited from a retired leaf, searchMenu attaches the
 * mapped tab value so the palette opens that host tab directly instead of the
 * default tab (e.g. the regression-suite word opens /cases?tab=suites).
 * Keys = live leaf paths;
 * every word must also exist in that leaf's menuSynonyms list (a word that
 * can never match would be dead code — pinned by
 * tests/comp/menuMergeZeroLossT407c.test.ts). Words are lower-cased because
 * the matcher compares in lower case; default-tab words are intentionally
 * absent (no tab = host falls back to its own default).
 */
export const synonymTabs: Record<string, Record<string, string>> = {
  '/cases': { 回归集: 'suites', 套件: 'suites', suite: 'suites' },
  '/skills': { 技能市场: 'market', 'skill market': 'market' },
  '/tools': { 工具市场: 'market', 'tool market': 'market' },
  '/extensions': { 插件: 'plugins', 行业插件: 'plugins', plugin: 'plugins' },
  '/report': {
    覆盖率: 'coverage',
    覆盖矩阵: 'coverage',
    coverage: 'coverage',
    报表中心: 'export',
    'report center': 'export',
  },
  '/batches': { 定时: 'fires', cron: 'fires', 调度触发: 'fires', schedule: 'fires' },
  '/execution': { 进程: 'process', 任务进程: 'process' },
  '/mock/spaces': { 流量检视: 'inspector', 检视器: 'inspector' },
  // AN-6: calibration words from the retired /usage-calibration leaf
  // open the /cost tab, not the default board (R24 face ③).
  '/cost': { 校准: 'calibration', 用量: 'calibration', 用量校准: 'calibration', 'token 校准': 'calibration', usage: 'calibration' },
}
