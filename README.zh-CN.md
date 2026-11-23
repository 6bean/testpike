# testPike — AI 自主测试用例生成与评审系统

> **[English](README.md)** | 简体中文

testPike 将需求文档转化为结构化测试用例与可执行浏览器脚本，并打通全链路闭环：
AI 生成 → 人工评审 → 真实执行 → 运营与治理。后端为 Java 21 微服务，控制台为 Vue 3，
执行侧为 Playwright 执行器集群。

## 功能特性

### AI 用例生成
- 从自然语言需求/用户故事生成单条或批量用例（单次上限 50 条，生成前成本预估确认）
- 覆盖缺口补齐生成、按既有用例库风格适配、提交前相似用例提示
- 结构化产出：步骤、前置条件、优先级与置信度建议、标签、端属性（web/移动/POS）

### 脚本生成与编辑
- 已评审用例自动转换为可执行动作 JSON 脚本，含元素定位策略生成
- 试运行验证、失败 L1 自愈、codegen 脚本规范化
- 双模编辑器：表单模式与源码模式实时 diff 预览、步骤树拖拽排序、loop/if 控制流
- 断言体系：视觉回归 + 非 UI 断言原语（console/网络/HTTP/SQL）、expect 匹配模式（范围/正则）

### 真实浏览器执行
- Python 3.11 + Playwright 执行器集群：注册 → 心跳 → 领取 → 执行 → 回传
- 任务级进程隔离（超时可靠 kill，浏览器失联免疫）
- 截图与产物存 SeaweedFS（S3 兼容对象存储）；自主页面探查（Agentic 模式）

### 评审工作台与用例治理
- 评审流带流程图生成与「描述↔流程图」双向同步
- 用例版本历史字段级 diff 与回滚、克隆、标签、分配、回收站误删恢复
- 追溯链视图（需求 → 用例 → 脚本 → 执行 → 缺陷）

### 测试运营
- 执行计划（含锁定模式）、回归集、批次调度（断点续跑 + 看门狗）
- 缺陷库、造数工厂、部署状态、进度看板、报表中心（统计口径固化）
- Flaky 用例检测与隔离、评审催办、每日日报摘要、数据保留策略配置化

### 知识库、SKILL 与 MCP
- 项目知识库基于 pgvector（语义检索注入生成 Prompt），支持全量重建与台账对账
- SKILL 定义/审批流/市场；工具注册与通用 ToolSPI、AI 消费链
- MCP：接入外部 MCP Server 作为工具，testPike 自身也可暴露为 MCP Server
- 反馈与学习：质量反馈接收、训练样本收集、优秀用例标记

### 平台与治理
- 多项目租户级数据隔离、项目级知识库/风格/环境配置、RBAC 权限
- LLM Provider 适配层（OpenAI 兼容）、密钥集中管理（AES）、Token 成本统计与预算闸
- Prompt 注入防护、训练数据脱敏、审计链完整性校验
- 安全运营：登录失败锁定、密码有效期策略、双 token 会话轮换、环境健康检查
- 通知中心（多渠道 + webhook）、国际化、深色（默认）/浅色主题、全局搜索
- CI/CD 集成端点（批次触发 API + 状态徽章）与 `cli/` 触发命令行；行业插件扩展点框架（POS/ERP 样例）

需求追踪矩阵共 147 个功能点，已交付 122 项，其余已排期。

## 架构

| 模块 | 职责 | 端口 |
| --- | --- | --- |
| `admin` | 面向用户的 API 入口（认证、工作台、CRUD 门面） | 8081 |
| `api` | 面向机器的对接入口（CI/CD、执行器协议、MCP Server） | 8082 |
| `core` | 服务层——领域逻辑、数据、调度（admin/api 经 Feign 调用） | 8083 |
| `ai-service` | AI 编排——LLM/Embedding 客户端、生成与评审管线 | 18084 |
| `common` | 跨模块共享基础件（Response、异常、校验） | — |
| `executor` | Python Playwright 集群（register/heartbeat/claim/result 协议） | — |
| `frontend` | Vue 3 + TypeScript + Element Plus 控制台 | 5173（dev） |

基础设施：PostgreSQL 16 + pgvector（15432）、Redis 7（6380）、RabbitMQ 3（5672）、SeaweedFS S3 网关（8333；元数据落 PG 独立库），
全部由 `docker compose` 提供。请求链路：前端 → admin → Feign → core / ai-service；异步任务走
RabbitMQ；产物经 S3 预签名 URL（SigV4）直传直取；S3_ENDPOINT 指云厂商端点即对接云对象存储。

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
> `db/schema-init.sql`（V1..迁移件 全量迁移+种子合并，244K，69 表）；若卷已有旧数据需重建：
> `docker compose down -v && docker compose up -d`。存量库升级仍按 `db/migration/` 增量手动执行
> （Flyway 未启用）。`db/schema-init.sql` 是生成物，新增迁移后跑 `bash tools/ops/gen_schema_init.sh`
> 再生，勿手改。
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

## License

Apache License 2.0（见 LICENSE）
