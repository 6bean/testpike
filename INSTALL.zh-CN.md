# testPike 装机手册（新用户）

> **[English](INSTALL.md)** | 简体中文

本手册带你从一台空机器走到「已验证、已登录」的 testPike：前置件、装机向导、配置、
数据库初始化、首次登录、装后验收清单。面向评估者与运维者；从源码跑服务的开发者可以
只看 [README 的快速开始](README.zh-CN.md#快速开始)（更短）。

## 1. 前置件

| 要求 | 说明 |
| --- | --- |
| Docker + Compose 插件 | 提供 PostgreSQL 16（含 pgvector）、Redis 7、RabbitMQ 3、SeaweedFS S3，高档位还包含应用/控制台/观测服务 |
| 主机上的 `git`、`curl`、`python3`（含 `pip`） | 向导最先自检这几件；缺件时点名缺了什么，并给出对应包管理器的安装命令 |
| 磁盘 / 内存 | 见 §2.3 档位表 |

Linux、macOS、WSL2 用 `installkit/install.sh`；Windows 用 `installkit/install.ps1`
（场景同构，底层步骤同一）。

## 2. 运行装机向导

### 2.1 一条命令

```bash
git clone <本仓库> testpike && cd testpike
cp .env.example .env          # 先检查/调整配置再装（见 §3）
bash installkit/install.sh --scenario=first
```

向导先问答（角色、资源档位、引擎、镜像源、是否自启——每题都带默认值，可一路回车），
然后按步骤安装。保证：

- **幂等**——已装组件自检并跳过，重复执行安全；
- **可续跑**——中断后从位点接续（`~/.testpike-installkit/state/`），`--restart` 清空位点从头再跑；
- **有日志**——全程日志落 `~/.testpike-installkit/logs/`；
- **不动你已有的服务**——端口被别的服务占用时人话点名，向导绝不代停非本人安装的组件；
- **不挂流水线**——`--unattended` 走同一条代码路径、全程取默认值。

### 2.2 场景一览

| 命令 | 用途 |
| --- | --- |
| `--scenario=first` | 首装：问答 → compose 基础栈 → 服务拉起 → 演示数据播种 → 试跑指引 |
| `--scenario=upgrade` | 一条命令升级已装实例：拉新代码（仅快进）→ 补数据库增量迁移 → 重建应用镜像 → 滚动重建容器 → 就绪探测；数据卷不动，每步断点可续 |
| `--scenario=repair` | 体检（探测件清单）+ 就地补齐缺件，补完复检 |
| `--scenario=scale-out --token=<一次性令牌> --base-url=http://<主机>:18082` | 新机器凭平台集群页生成的一次性令牌接入执行器池 |
| `--scenario=scale-in` | 先排空队列，再只卸载 installkit 自己安装的东西 |
| `--scenario=engines --engine=<引擎码> [--dry-run]` | 按描述符换/加装压测引擎（如 k6、Gatling） |

### 2.3 资源档位

向导会问档位；也可用 `--tier=lightweight|standard|full` 直接指定。磁盘/内存数字由
组件矩阵程序化求和，不是手写字面值。

| 档位 | 组件 | 磁盘 | 内存 |
| --- | --- | --- | --- |
| 轻量体验 `lightweight`（默认） | PostgreSQL / Redis / RabbitMQ / SeaweedFS + 后端服务 | 2700 MB | 3968 MB |
| 标准上手 `standard` | + nginx 承载的网页控制台 | 2900 MB | 4032 MB |
| 完整功能 `full` | + Prometheus / Grafana 观测 + Playwright 执行器 | 4587 MB | 4864 MB |

轻量档只提供接口面——控制台需自行以开发模式启动（`cd frontend && npm install &&
npm run dev`，端口 5173），或直接选标准档让向导替你装好控制台。

## 3. 配置：一切取值都在 `.env`

装机可调的全部取值（端口、库名、对象桶、消息 vhost、阈值）一律从项目根 `.env` 读取，
键集与出厂缺省在 `.env.example` 里有注释。改 `.env`，不改脚本；`.env` 已被 gitignore
排除，永不入库。

首次启动前值得过一眼的键：

| 键 | 样例值 | 含义 |
| --- | --- | --- |
| `ADMIN_INIT_PASSWORD` | `Admin#123` | core 首次启动时用它建 `admin` 账号；此键缺失则不建任何账号（宁可缺不将就）。**登录后请立即改密。** |
| `LLM_MOCK_ENABLED` | `false` | `true`=内置 mock 替身：零外网、零密钥也能全链路跑通；`false`=真实模式，把 `LLM_BASE_URL` / `LLM_API_KEY` / `LLM_MODEL` 指向任意 OpenAI 兼容网关 |
| `TP_WEB_HOST_PORT` | `18080` | 浏览器入口（标准/完整档） |
| `APP_ADMIN_HOST_PORT` | `18081` | 面向用户的 API 网关（演示数据播种也走它） |
| `APP_API_HOST_PORT` | `18082` | 面向机器的接口（CI/CD、执行器协议、MCP） |
| `APP_CORE_HOST_PORT` | `18083` | 内部服务层（仅本机回环，不在用户链路上） |
| `APP_AISERVICE_HOST_PORT` | `18084` | AI 编排服务（仅本机回环） |
| `LLM_TIMEOUT_SECONDS` | `800` | 单次大模型请求超时；长生成建议保持 ≥ 800 |

### 端口对照表（出厂缺省）

| 服务 | 宿主端口 | | 服务 | 宿主端口 |
| --- | --- | --- | --- | --- |
| 网页控制台 | **18080** | | PostgreSQL | 15432 |
| admin | 18081 | | Redis | 6380 |
| api | 18082 | | RabbitMQ 消息 / 管理台 | 5672 / 15672 |
| core（内部） | 18083 | | SeaweedFS S3 | 8333 |
| ai-service（内部） | 18084 | | SeaweedFS 控制台（需登录） | 9000 / 9001 |
| Prometheus | 19090 | | Grafana | 13000 |

以上全部是「配置键+出厂缺省」形态（`${键:-缺省值}`），在 `.env` 里设键即可挪端口。
从源码跑服务（开发形态）时用容器内端口：admin 8081、api 8082、core 8083、
ai-service 18084、前端 dev 5173、演示靶站 8899（`executor/` 下 `python3 serve_fixtures.py`）。

## 4. 数据库初始化

全新安装零手动：

- 空数据卷上首次 `docker compose up -d` 自动执行 `db/schema-init.sql`——全部迁移件与
  出厂种子合并（69 表：角色权限、系统配置出厂值、提示词包、数据字典、演示账号）。
- 推倒重建：`docker compose down -v && docker compose up -d`。
- 存量库升级：跑 `bash installkit/install.sh --scenario=upgrade`，按迁移台账自动补放
  `db/migration/` 增量脚本（未启用 Flyway；详见 §7）。
  `db/schema-init.sql` 是生成物，新增迁移后跑 `bash tools/ops/gen_schema_init.sh` 再生，勿手改。

## 5. 首次登录

1. 打开控制台：`http://localhost:18080`（标准/完整档；开发模式为 `http://localhost:5173`）。
2. 用账号 **`admin`** + 你 `.env` 里的 `ADMIN_INIT_PASSWORD`（样例值 `Admin#123`）登录。
   登录后立即改密——不改的话，密码到期策略会在下次登录时强制你改。
3. 可选，零成本逛一圈——出厂内置五个演示账号，每个角色一个，共用演示口令
   **`Demon@tp2026`**：

   | 账号 | 角色 | 能体验什么 |
   | --- | --- | --- |
   | `demo_developer` | 开发人员 | 编写用例/脚本、试跑 |
   | `demo_tester` | 测试人员 | 执行面：跑批次 |
   | `demo_test_lead` | 测试负责人 | 评审与分配 |
   | `demo_project_manager` | 项目经理 | 管计划与审批、不执行 |
   | `demo_admin` | 系统管理员 | 全功能控制台（含审计） |

   这组是开源随包演示种子、不是真实秘密——实例一旦要暴露到任何真实网络，先删除或改掉它们。

首装向导结束时还会自动播种演示内容（样例项目+一条用例、五分钟引导旅程、订单/库存缩微
应用与一条演示性能场景）。若那一刻平台还没就绪，向导会人话说明并打印一条可复读重跑的命令：

```bash
python3 installkit/s1_bootstrap.py seed-demo --base-url http://localhost:18081
```

播种幂等——重跑时同名条目直接复用，不产生重复数据。

## 6. 装后验收清单

自上而下逐项过；每行一条命令（或一次点击）+ 通过标准。

| # | 检查项 | 命令 / 动作 | 通过标准 |
| --- | --- | --- | --- |
| 1 | 容器健康 | `docker compose ps` | 所选档位全部 `healthy`（应用首次启动健康检查约需 1–2 分钟） |
| 2 | 四服务在线 | `curl -s http://127.0.0.1:18081/actuator/health`（再依次 `18082`、`18083`、`18084`） | 各自返回 `{"status":"UP"}` |
| 3 | 体检探测件 | `python3 installkit/doctor.py --state-dir ~/.testpike-installkit/state` | 无阻断级缺件；提示级缺件附修复命令 |
| 4 | 控制台开门 | 浏览器打开 `http://localhost:18080` | 登录页正常渲染（浏览器无报错） |
| 5 | 首登 | 用 `admin` + `ADMIN_INIT_PASSWORD` 登录 | 落地到仪表盘 |
| 6 | 种子数据可见 | 项目列表 | 能看到样例项目、五分钟旅程件、「订单中心」「库存中心」 |
| 7 | AI 链路可跑 | 任意生成入口 | `LLM_MOCK_ENABLED=true` 时 mock 回答全链路走通（零外网）；`false` 时从所配网关出答案 |
| 8 | 执行器池（完整档） | 执行器机跑 `python3 executor/runner.py`，再看控制台执行器页 | runner 注册成功、在线并有心跳 |
| 9 | 端到端冒烟 | 挑一条出厂接口用例旅程对内置靶站执行 | 批次到终态，报告/截图可回看 |
| 10 | 日志在位 | `ls ~/.testpike-installkit/logs` | 有当次安装的日志文件 |

任何一项不过：跑 `bash installkit/install.sh --scenario=repair`——重探、就地补缺、
结构化报告、补完复检。

## 7. 升级、扩容、退场

- **升级**：进入安装目录，`git pull` 后跑
  `bash installkit/install.sh --scenario=upgrade`。向导只接受快进拉新（本地有未提交
  改动或分支分叉会点名拒件，绝不改写历史），按迁移台账补放你库基线之后的
  `db/migration/*.sql` 增量，重建应用镜像，按装机资源档位原地滚动重建服务，
  最后等待 admin 就绪收尾。已有数据卷全程不动。任一步失败都会点名原因与修法，
  重跑同一命令从断点续；拉到更新代码后再跑会自动重走全流程。
- **加执行器**：平台集群页生成一次性令牌，新机器跑
  `bash installkit/install.sh --scenario=scale-out --token=<令牌> --base-url=http://<平台主机>:18082`。
- **机器退场**：`--scenario=scale-in` 先排空在跑队列，再只移除 installkit 装的部件。
- **移动端（Android）旅程**：真机或模拟器接入已入池的执行器（`adb devices` 可见）；
  API/Web 旅程无需设备。

## 8. 常见问题速查

| 现象 | 大概率原因 | 处理 |
| --- | --- | --- |
| 向导点名端口被占用后停下 | 你已有的别的服务占着该端口 | 自行确认后停它，或在 `.env` 改对应端口键，重跑同一命令（从断点续） |
| core 反复重启、报 AMQP 530 | 全新消息队列上自定义 vhost 还没建 | 重跑 `--scenario=first`（或 repair），vhost 对账是记账位点，补齐后自动续 |
| 登录提示已锁定 | 连续输错密码 | 锁定有到期时间，等一等；或 `docker exec <postgres容器> psql -U testpike -d testpike -c "update tp_user set fail_count=0, locked_until=null where username='admin'"` |
| AI 功能超时 | 真实网关不可达 / 超时给小了 | 核对 `LLM_BASE_URL`/`LLM_API_KEY`；`LLM_TIMEOUT_SECONDS` 保持 ≥ 800；先用 `LLM_MOCK_ENABLED=true` 把网络因素摘出去 |
| 拉镜像慢 | 所在网络到官方仓库延迟高 | 首装加 `--mirror-base-url=<内网镜像根>`，或在平台集群页设镜像键 |
| 中间件起不来，报 `failed to resolve reference` / `i/o timeout` | 本机到 Docker Hub 直连不通（内网封锁/DNS 污染常见） | 装机已自动改道：逐个镜像经加速器取回并改成本机认得的名字（不需要管理员）；引擎 `daemon.json` 可写时还会合并 `registry-mirrors` 后重启，且检测到非本系统的在跑容器就不重启、只给命令。候选与超时全部可热更：`TP_DOCKER_MIRRORS=<地址>[,...]`、`TP_DOCKER_RELAY=off` 可整体关停、`TP_DOCKER_*_TIMEOUT_S`；全不可用时脚本给出离线摆渡配方（另一台机 `docker save` → 拷 tar → 本机 `docker load`）。修好后原样重跑同一命令即续跑 |
| 说不清哪坏了 | — | 跑 `--scenario=repair`，再看 `~/.testpike-installkit/logs/` |

---

验收通过后：登录，按仪表盘的首登引导浮层走一遍五分钟旅程（生成 → 评审 → 执行 → 报告）。
