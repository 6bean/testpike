# testPike — 自进化的 AI 测试平台

> **[English](README.md)** | 简体中文

testPike 将需求文档转化为结构化测试用例与可执行脚本，在真实浏览器、真实设备与真实压测引擎上执行，
并从每一次评审与每一次失败中学习。一条完整闭环：**需求 → AI 生成 → 人工评审 → 真实执行 →
运营与治理 → 反馈回流到提示词、技能与样本。**

Java 21 微服务 · Vue 3 控制台 · Python 3.11 + Playwright 执行器集群 · pgvector 知识库 ·
MCP 双向打通 · 内置 mock LLM，零外网 API 也能全链路跑通。

```
 需求文档 ──AI 提取（逐块证据账本）──▶ 需求点
 需求点 ──AI 生成（先成本预估，再一键确认）──▶ 用例
 用例 ──评审工作台（流程图同步、快捷键评审）──▶ 已评审用例
 已评审用例 ──脚本引擎（动作 JSON、定位策略生成）──▶ 可执行脚本
 脚本 ──执行器集群──▶ Web UI · 接口 · 移动端 · 性能 执行
 结果 ──▶ 报告、缺陷、成本台账 ──▶ 反馈 / 学习 / 自进化
```

## 功能特性

### 需求与用例设计
- 导入 Markdown/PDF 需求文档；AI 提取需求点，带逐块证据账本（重提取与旧版本做差异比对、
  变更/缺失项进复核队列、预览不静默丢弃原文内容）
- 需求台账：需求点版本化，文档 → 需求点 → 用例血缘可查
- AI 用例生成：单条或批量（缺省单次 50 条，上限热更新可调），生成前成本预估一键确认；
  覆盖缺口补齐；按既有用例库风格适配；提交前相似用例提示；场景圈选默认完整枚举
  （一个需求点生成多条用例）
- 结构化产出：步骤、前置条件、优先级与置信度建议、标签、端属性（Web/移动/POS）
- 评审工作台：快捷键评审与批量模式、流程图生成与「描述 ↔ 流程图」双向同步、
  结构化驳回类型回流学习环
- 用例治理：字段级版本历史与回滚、克隆、标签、分配、回收站；追溯视图
  （需求 → 用例 → 脚本 → 执行 → 缺陷）
- Web 元素台账：页面元素统一登记复用，供定位策略消费

### 脚本与真实执行
- 已评审用例自动转换为可执行动作 JSON，含元素定位策略生成；试运行验证、失败 L1 自愈、
  codegen 脚本规范化
- 双模编辑器：步骤树拖拽排序 + loop/if 控制流，源码模式实时 diff 预览
- 断言体系：视觉回归（基线经人工确认）+ 非 UI 断言原语（console/网络/HTTP/SQL），
  expect 支持包含/精确/正则/范围匹配
- 执行器集群协议：注册 → 心跳 → 领取 → 执行 → 回传；任务级进程隔离（超时可靠 kill，
  浏览器失联免疫）
- 按环境的浏览器矩阵（chromium / firefox / webkit）；截图与产物存 S3 兼容对象存储
  （compose 自带 SeaweedFS，`S3_ENDPOINT` 指云厂商端点即对接云存储）
- 调度与韧性：批次断点续跑、看门狗、崩溃恢复、单条重跑、快速一次性执行；
  Flaky 用例检测与隔离
- 自主页面探查（Agentic 模式），带步数/token/时长三重预算与「证据 → 草案」管线

### 接口测试、契约仿真与编排
- 接口测试工作台：接口集合、五类认证 + 签名预设、JSON Schema 校验、WebSocket 断言、
  集合级认证/默认头继承与最终生效值预览、响应差分、CI 准出判定
- 接口清单与契约变更分析：规范导入、接口台账、契约变更影响清单指向受影响的用例与仿真
- Mock 服务：由接口规范生成契约驱动的仿真空间——确定性匹配带示例响应，未命中流量可选
  AI 兜底（诚实标注、可检视、绝不静默编造）
- 流程编排：接口流程带模板与一键升格复用；跨项目测试旅程打通多系统场景

### 性能测试
- 三引擎——Gatling（缺省）、k6、JMeter——经装机向导引擎描述符安装或换装
- 场景向导、实时指标运行页、运行中 SLA 熔断与急停开关、版本间报告对比
- 接口测试一键升格为压测场景；结果进同一套报告与缺陷链路

### 移动 App 自动化
- 设备墙与预约日历；跨项目共享设备，带租约与写锁
- Android 真机/模拟器接入已入池执行器；引擎中心支持平台适配器插拔
  （鸿蒙 HarmonyOS NEXT 设备适配契约已冻结，在路线图推进中）
- 应用包管理、移动端元素库、弹窗规则、流程画布编辑、步骤级 AI 修复、AI 样本看板；
  运行报告收录崩溃现场步骤序列；带预算的 App 自动探查

### AI 副驾与自进化
- 每页常驻对话浮坞：流式帧带心跳与诚实降级、可钉住常驻、未读角标、会话库
  （搜索/分页/找回）；`@` 引用把技能与工具拉进上下文
- 草案卡：助手可新建/编辑用例、需求点、脚本、计划等——一切写操作先落成可复核草案、
  一键接受才生效；未经确认不碰你的数据
- 有据回答：引用可深链到帮助章与知识条目；截图、Excel 表格（自动转 Markdown）直接黏贴进
  对话；失败现场截图交给视觉模型做诊断
- 项目知识库基于 pgvector（语义检索注入生成 Prompt），支持全量重建与台账对账
- SKILL 定义/审批流/市场；工具注册与通用 ToolSPI、函数调用消费链（HTTP 回调、执行器沙箱、
  MCP 桥三形态）；写类工具由一次性确认票守卫
- MCP 双向：接入外部 MCP Server 作为工具；testPike 自身也暴露为 MCP Server，
  外部编码智能体可驱动全部功能面
- 学习环：质量反馈采集、采纳话术注入、训练样本收集、Prompt 模板版本化/灰度流量/回滚，
  以及自进化环——AI 给自己的提示词提改进草案，一键回退
- AI 评测（judge）：评测集、运行与报告，给平台自身的 AI 产出打分
- 异步可见性：长任务带实时尾随窗口，终态后可回放进程详情，离开页面也找得回发生的事
  （正在向全部异步面推广）
- 成本中心：逐次调用 token 记账、预算熔断闸、按会话成本归因

### 测试运营与洞察
- 执行计划、回归集、项目集（program）大盘、进度看板
- 报表中心：统计口径固化、周报月报订阅、CSV/PDF 导出、订阅块；批次完成卡片
- 缺陷库：失败自动建单建议 + JIRA 同步；造数工厂（数据池加密存储）；站点与接口监控；
  环境与部署状态跟踪
- 通知中心：站内信、Webhook、飞书交互卡片（带回调闸）；评审催办、每日日报摘要；
  死信运营台账与受治理重放
- 数据保留策略配置化；审计链完整性校验

### 平台与治理
- 多项目租户级数据隔离，项目级知识库/风格/环境配置；RBAC 角色模板与演示账号；
  删除请求显式审批流
- 凭据保险箱（加密存储、永不回显）与登录态采集重放（token / storage_state / cookie_set，
  支持一个会话携多凭据的组合登录态），用于验证码、扫码、SSO 登录场景——采集仅限本人
  已登录会话；验证码「破解」明确不是功能
- 安全：Prompt 注入防护、执行点权限交集复校、登录失败锁定、密码有效期策略、
  双 token 会话轮换、环境健康检查
- 集群管理：执行器池与队列水位、升级中心（灰度发布）、机群弹性伸缩决策核
  （裸金属节点 agent 入池已就绪；K8s/Helm 形态在验证轨道）
- 行业插件框架（POS/ERP 描述符样例）与 CI/CD 集成端点（批次触发 API + 状态徽章、
  `cli/` 触发命令行）
- OpenAI 兼容 LLM Provider 适配层、密钥集中管理（AES）；内置 mock LLM 零外网即可全功能运行
- 国际化（简中/英文）、深色（默认）/浅色主题、`Ctrl/⌘+K` 全局搜索（含菜单同义词）、
  逐页「？」帮助章

**交付状态：** 需求追踪矩阵共登记 **202 个功能点，其中 197 个已有完成实证**，其余在开发中或已排期。

## 路线图

进行中
- 整站测绘：输入一个 URL 递归测绘整站生成站点档案（按路径段切分项目、登录墙诚实判定），
  供页面探查与用例生成消费
- 进程回放窗口覆盖其余全部异步发起面（离开页面后仍可回看进度）
- 提取链吞吐攻坚：大文档提取从小时级压向分钟级，且不放松保真闸

规划中 / 在推进
- 面向模型微调的训练数据脱敏管线
- 移动端真机机队扩展与鸿蒙设备适配器落地
- 浏览器矩阵分浏览器录屏生成
- K8s 原生弹性伸缩形态活体验证
- 需求文档更新触发的增量生成用例与一键回归批次

## 架构

| 模块 | 职责 | 端口 |
| --- | --- | --- |
| `admin` | 面向用户的 API 入口（认证、工作台、CRUD 门面） | 8081 |
| `api` | 面向机器的对接入口（CI/CD、执行器协议、MCP Server） | 8082 |
| `core` | 服务层——领域逻辑、数据、调度（admin/api 经 Feign 调用） | 8083 |
| `ai-service` | AI 编排——LLM/Embedding 客户端、生成与评审管线 | 18084 |
| `mock-server` | 契约驱动的接口仿真服务 | 18085（管理）/ 18086（流量） |
| `common` | 跨模块共享基础件（Response、异常、校验） | — |
| `executor` | Python 执行器集群：浏览器/App/接口/性能四类任务（注册/心跳/领取/回传） | — |
| `frontend` | Vue 3 + TypeScript + Element Plus 控制台 | 5173（dev） |
| `installkit` | 装机向导、资源档位、引擎描述符、机群入池 | — |
| `cli` | CI 触发命令行（runs API + 状态徽章） | — |

基础设施：PostgreSQL 16 + pgvector（宿主 15432）、Redis 7（6380）、RabbitMQ 3（5672）、
SeaweedFS S3 网关（8333；元数据落 PG 独立库），全部由 `docker compose` 提供；`full` 档再加
Prometheus/Grafana。请求链路：前端 → admin → Feign → core / ai-service；异步任务走 RabbitMQ；
产物经 S3 预签名 URL（SigV4）直传直取。所有宿主端口经 `.env` 可重映射。

## 安装

新用户请按独立手册 [INSTALL.zh-CN.md](INSTALL.zh-CN.md) 一步步走——前置件、向导场景、
`.env` 配置、数据库初始化、首次登录、装后验收清单（[英文版](INSTALL.md)）。下方为同口径简版。

### 装机向导（Linux / macOS / WSL）

```bash
bash installkit/install.sh --scenario=first
```

首装向导以问答确定资源档位，然后按步骤安装整套服务：每一步幂等（已装组件自检并跳过）、
中断后按步骤位点续跑、全程日志落 `~/.testpike-installkit/logs/`；`--scenario=repair`
提供体检修复，就地补齐缺件。

三档资源（磁盘/内存数字由组件矩阵程序化求和，非手写字面值；也可用
`--tier=lightweight|standard|full` 直接指定，跳过问答）：

| 档位 | 组件 | 磁盘 | 内存 |
| --- | --- | --- | --- |
| 轻量体验（默认） | PostgreSQL / Redis / RabbitMQ / SeaweedFS + 后端服务 | 2700 MB | 3968 MB |
| 标准上手 | + nginx 承载的控制台（compose profile `app`） | 2900 MB | 4032 MB |
| 完整功能 | + Prometheus / Grafana 观测 + Playwright 执行器 | 4587 MB | 4864 MB |

前置件：Docker（含 Compose 插件），以及主机上的 git、curl、python3（含 pip）——向导先自检，
缺件时点名缺了什么并给出对应包管理器的安装命令。Windows 用 `installkit/install.ps1`
（场景同构）。`--unattended` 与交互问答走同一条代码路径、全程取默认值，CI 管道不会挂在追问上。

其余入口：`--scenario=scale-out` 凭一次性入池令牌把新机器接入执行器池；`--scenario=scale-in`
先 drain 再卸载，且只卸载 installkit 自己安装的组件；`--scenario=engines` 按描述符换/加装压测引擎。
装机可调的取值（端口、库名、对象桶、消息 vhost、阈值）一律从项目根 `.env` 读取——键集与出厂
缺省见 `.env.example`，改 `.env` 即可，不改脚本。

移动端（Android）旅程需要把真机或模拟器接入已入池的执行器；API/Web 旅程无设备即可运行。

> 开源装机策略：向导遵循开源平台通用惯例（一条命令引导、步骤幂等、资源档位程序化求和）。
> 设计思路参考过 TestHub 等项目，但未复制任何 GPL 源码——`installkit/` 下每个文件均为
> 本仓库原创实现。

## 快速开始

### 1. 基础设施（Docker）

```bash
docker compose up -d      # PostgreSQL 16（宿主 15432）+ Redis(6380) + RabbitMQ(5672) + SeaweedFS(8333)
```

### 2. 后端（JDK 21 + Maven）

```bash
# 全量编译
mvn -q compile
# 单测（模块级）
mvn -pl core test
# 启动服务（各模块目录）
mvn spring-boot:run       # admin:8081 / api:8082 / core:8083 / ai-service:18084
```

> 环境变量：`cp .env.example .env` 起步（样例值与 docker compose 开发口令一致，开箱可跑），
> 应用从项目根 `.env` 读取（密钥禁内联；`set -a && source .env && set +a`）。`.env` 已被
> .gitignore 排除，永不入库。
> 数据库初始化：全新安装零手动——`docker compose up -d` 首次启动（空数据卷）自动执行
> `db/schema-init.sql`（全量增量迁移史与种子数据合并产物：约 2.2 MB、199 表）；若卷已有旧数据
> 需重建：`docker compose down -v && docker compose up -d`。存量库升级仍按 `db/migration/`
> 增量手动执行（Flyway 未启用）。`db/schema-init.sql` 是生成物，新增迁移后跑
> `bash tools/ops/gen_schema_init.sh` 再生，勿手改。
> LLM：默认 `LLM_MOCK_ENABLED=true` 走内置 mock 替身，无外网 API 也能全链路跑通；
> 关闭后把 `LLM_BASE_URL` / `LLM_API_KEY` 指向任意 OpenAI 兼容网关即可。

### 3. 前端（Vue 3）

```bash
cd frontend
npm install && npm run dev     # http://localhost:5173
npm run build                  # 生产构建（NODE_OPTIONS=--max-old-space-size=4096 防 OOM）
npx vue-tsc --noEmit           # 类型检查
```

### 4. 执行器（Python 3.11，真实浏览器执行）

```bash
cd executor
pip install -r requirements.txt
python3 runner.py              # 注册 → 心跳 → 领取 → 子进程执行 → 回传
```

> `python3 serve_fixtures.py &` 可启动本地演示目标页（8899），无需真实被测系统即可端到端跑通脚本。

## 文档与社区

- [INSTALL.zh-CN.md](INSTALL.zh-CN.md) / [INSTALL.md](INSTALL.md) — 装机手册与验收清单
- [CONTRIBUTING.md](CONTRIBUTING.md) — 提 issue、交补丁、新增技能/工具的规矩
- [SECURITY.md](SECURITY.md) — 漏洞披露与坚持的安全不变量
- [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) · [NOTICE](NOTICE) — 第三方署名台账

## 许可协议

Apache License 2.0（见 [LICENSE](LICENSE)）
