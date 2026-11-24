# =============================================================================
# testPike installkit 装机入口（Windows PowerShell 5.1 基线；Linux/macOS/WSL 用 install.sh）
# AB 波 T315 包③骨架：场景框架 S1-S5 + 步骤位点续跑 + 日志落盘 + 入池心跳回传。
# 与 install.sh 同一契约行（03 协议装机面，单源）：enroll/heartbeat POST body
#   = {token, fingerprint}，只经 api /internal 入口（体系外禁直连 core）。
# 红线：明文令牌只进请求体，本脚本日志零令牌；卸载只删 installkit 自装件。
# =============================================================================
param(
    [string]$Scenario = "",
    [string]$Role = "",
    [string]$Token = "",
    [string]$BaseUrl = "",
    [string]$MirrorBaseUrl = "",
    [string]$Engine = "",
    [string]$Action = "install",
    [string]$StateDir = "$env:USERPROFILE\.testpike-installkit\state",
    [string]$LogDir = "$env:USERPROFILE\.testpike-installkit\logs",
    [switch]$Restart
)
$ErrorActionPreference = "Stop"

function Show-Usage {
@"
用法: .\install.ps1 -Scenario <场景> [-Role .. -Token .. -BaseUrl ..]
场景: first(S1) scale-out(S2) scale-in(S3) engines(S4) repair(S5)，别名 S1-S5
选项: -Role executor|ai-node|platform  -Token 一次性入池令牌  -BaseUrl api 地址
      -MirrorBaseUrl 内网镜像根  -Restart 清位点从头再跑
幂等可重跑、中断可续（位点文件）、全程日志落盘、失败即给出原因与修复入口。
"@ | Write-Host
}

$Aliases = @{ S1 = "first"; S2 = "scale-out"; S3 = "scale-in"; S4 = "engines"; S5 = "repair" }
if ($Aliases.ContainsKey($Scenario)) { $Scenario = $Aliases[$Scenario] }
if ([string]::IsNullOrWhiteSpace($Scenario)) {
    Write-Error "缺少 -Scenario=<场景>（可选 first/scale-out/scale-in/engines/repair，别名 S1-S5）"
    Show-Usage; exit 2
}
if ($Scenario -notin @("first", "scale-out", "scale-in", "engines", "repair")) {
    Write-Error "未知场景「$Scenario」——可选：first / scale-out / scale-in / engines / repair（别名 S1-S5）"
    exit 2
}

New-Item -ItemType Directory -Force -Path $StateDir, $LogDir | Out-Null
$State = Join-Path $StateDir "state-$Scenario.txt"
$LogFile = Join-Path $LogDir ("installkit-{0}.log" -f (Get-Date -Format "yyyyMMdd"))
if ($Restart) { "" | Set-Content -Path $State -Encoding UTF8 }

function Write-Log([string]$m) { ("[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $m) | Add-Content -Path $LogFile -Encoding UTF8 }
Write-Log "=== scenario=$Scenario start ==="

function Say([string]$m) { Write-Host $m; Write-Log "say: $m" }

# 位点续跑：成功才记账，已记账步骤跳过（幂等可重跑、零重复外呼）
function Run-Step([string]$Id, [string]$Desc, [scriptblock]$Body) {
    if ((Test-Path $State) -and ((Get-Content $State -Encoding UTF8) -contains "step:$Id")) {
        Write-Log "skip=$Id"
        Write-Host "步骤[$Desc]已完成（位点续跑跳过）"
        return $true
    }
    Write-Log "step=$Id start: $Desc"
    try { & $Body } catch { Write-Log "step=$Id failed"; Write-Error $_; return $false }
    "step:$Id" | Add-Content -Path $State -Encoding UTF8
    Write-Log "step=$Id done"
    return $true
}

function Get-MachineFingerprint {
    # 机器指纹=稳定标识 sha256（Windows 侧取 MachineGuid 注册表，缺失回退主机名）
    $material = $null
    try {
        $material = (Get-ItemProperty "HKLM:\SOFTWARE\Microsoft\Cryptography" -Name MachineGuid -ErrorAction Stop).MachineGuid
    } catch { $material = $null }
    if ([string]::IsNullOrWhiteSpace($material)) { $material = [System.Net.Dns]::GetHostName() }
    $sha = [System.Security.Cryptography.SHA256]::Create()
    ($bytes = $sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes("testpike-installkit:$material"))) | ForEach-Object { "{0:x2}" -f $_ }
}

function Invoke-EnrollApi([string]$Path, [string]$TokenValue) {
    $body = @{ token = $TokenValue; fingerprint = (Get-MachineFingerprint) -join "" } | ConvertTo-Json -Compress
    try {
        $resp = Invoke-RestMethod -Method Post -Uri ($BaseUrl.TrimEnd('/') + $Path) `
            -ContentType "application/json" -Body $body -TimeoutSec 15
    } catch {
        Write-Error "平台不可达：$BaseUrl——请检查 api 服务在位后重跑同一命令（位点续跑不会重复入池）"
        return $null
    }
    if ($resp.code -ne "0") {
        Write-Error ($(if ($resp.msg) { $resp.msg } else { "平台拒绝（未返回原因）" }))
        return $resp
    }
    return $resp
}

$Py = if ($env:PYTHON_BIN) { $env:PYTHON_BIN } else { "python" }

# S1 同源消费：问答/拉起/试跑全部调 python 单源件（s1_wizard/s1_bootstrap，
# §9.0 单源承诺=禁 PS 二套问答；失败 throw 走 Run-Step 位点语义=不记账可续跑）
function Invoke-Py([string]$ScriptName, [string[]]$PyArgs) {
    & $Py (Join-Path $PSScriptRoot $ScriptName) @PyArgs
    if ($LASTEXITCODE -ne 0) { throw ("{0} 失败（退出码 {1}）——原因与修复入口见上方输出，修复后重跑同一命令即从断点续跑" -f $ScriptName, $LASTEXITCODE) }
}

$rc = 0
switch ($Scenario) {
    "first" {
        # 预检 Windows 画像行为级随 T316 收口补账（本机 WSL 仅语法级实证，行为级不可达如实标注）
        Run-Step "preflight" "环境预检" { Say ("预检：OS=Windows {0} PowerShell {1} python={2}" -f [System.Environment]::OSVersion.Version, $PSVersionTable.PSVersion, $Py) } | Out-Null
        # 镜像源键拉取与 install.sh 同构（§9.5-5；CLI 显式优先，失败人话降级官方源不阻断）
        if (-not $MirrorBaseUrl -and $BaseUrl) {
            $fetched = & $Py (Join-Path $PSScriptRoot "enroll_client.py") fetch-mirror --base-url $BaseUrl
            if ($LASTEXITCODE -eq 0 -and $fetched) { $MirrorBaseUrl = "$fetched".Trim() }
            Write-Log "mirror-key fetched=$MirrorBaseUrl"
        }
        Run-Step "role-question" "角色与引擎问答" {
            Invoke-Py "s1_wizard.py" @("--answers-file", (Join-Path $StateDir "answers-S1.json"), "--mirror-default", $MirrorBaseUrl)
        } | Out-Null
        # T336 刀4 S1 运行时前置位点（与 install.sh 同构，needs 闸判定单源在件内）：
        # --yes 依据=用户显式跑装机脚本即同意零前置自动补齐（§48 无开发经验人员口径）。
        & $Py (Join-Path $PSScriptRoot "runtime_bootstrap.py") needs --state-dir $StateDir --answers-file (Join-Path $StateDir "answers-S1.json") 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) {
            Run-Step "runtime-bootstrap" "引擎运行时前置" {
                Invoke-Py "runtime_bootstrap.py" @("ensure", "--yes", "--state-dir", $StateDir)
            } | Out-Null
        }
        Run-Step "compose-seed" "服务与样例种子" {
            Invoke-Py "s1_bootstrap.py" @("compose", "--answers-file", (Join-Path $StateDir "answers-S1.json"))
        } | Out-Null
        Run-Step "seed-demo" "demo data 自动播种" {
            Invoke-Py "s1_bootstrap.py" @("seed-demo")
        } | Out-Null
    }
    "scale-out" {
        if ($Role -and ($Role -notin @("executor", "ai-node", "platform"))) {
            Write-Error "机器画像「$Role」不在值域——必须是 executor / ai-node / platform 之一（令牌签发时三画像，脚本侧禁自造角色）"
            exit 2
        }
        if (-not $Token) {
            Write-Error "缺少 -Token——入池令牌请在机群页「添加执行器/AI 节点」生成带 token 命令复制（一次性+TTL，过期重生成一条即可）"
            exit 2
        }
        if (-not $BaseUrl) {
            Write-Error "缺少 -BaseUrl——api 服务地址（如 http://<平台机>:8082），装机脚本只经 api 入口禁直连 core"
            exit 2
        }
        if (Run-Step "hb-start" "开工打点" { Invoke-EnrollApi "/internal/cluster/enroll/heartbeat" $Token }) {
            $ok = $true
            Run-Step "enroll-token" "令牌核销入池" {
                $r = Invoke-EnrollApi "/internal/cluster/enroll" $Token
                if (-not $r -or $r.code -ne "0") { throw "核销未成功" }
                Say ("已入池：角色={0} 作用域={1}" -f $r.data.role, $(if ($r.data.projectScope) { $r.data.projectScope } else { "全部项目" }))
            } | Out-Null
        }
        Run-Step "hb-done" "收尾打点" { Invoke-EnrollApi "/internal/cluster/enroll/heartbeat" $Token } | Out-Null
    }
    "scale-in" {
        Run-Step "drain-check" "先排空" { Say "退池必先 drain：停 claim→在跑任务归零→再退（沿用 AA 语义，骨架位点）" } | Out-Null
        Run-Step "uninstall-boundary" "卸载边界" { Say "安全边界声明：只卸载 installkit 自己安装的东西（版本目录+服务+台账），用户手装件永不触碰" } | Out-Null
        # 退池卸载段实装（T318 包④，与 install.sh purge 同构）：账内件清单判定全在
        # ledger.py uninstall-plan 单源（§9.0 禁 PS 二套账闸），逐引擎跑 recipe.py 卸载命令+销账。
        Run-Step "purge-self-installed" "卸载自装引擎件" {
            $plan = ""
            try { $plan = (& $Py (Join-Path $PSScriptRoot "ledger.py") uninstall-plan --state-dir $StateDir --host windows) -join "`n" }
            catch { throw "卸载计划判定失败：$_" }
            if (-not $plan.Trim()) { Say "本机无 installkit 自装引擎件（装机账为空）——退池零卸载零触碰，用户手装件永不在此列"; return }
            foreach ($line in ($plan -split "`n" | Where-Object { $_.Trim() })) {
                $code, $pin = $line -split "`t", 2
                Run-Step "uninstall-engine:$code" "卸载自装引擎「$code」" {
                    $rf = Join-Path $StateDir "recipe-$code.json"
                    if (-not (Test-Path $rf)) { Say "引擎「$code」卸载跳过——配方不在盘；账面保留（卸不掉不销账，账实一致）"; return }
                    $cmds = (& $Py (Join-Path $PSScriptRoot "recipe.py") uninstall --recipe-file $rf --host windows) -join "`n"
                    if ($LASTEXITCODE -ne 0) { throw "配方命令取回失败：$cmds" }
                    foreach ($c in ($cmds -split "`n" | Where-Object { $_.Trim() })) {
                        Say "执行配方命令：$c"
                        Invoke-Expression $c
                        if ($LASTEXITCODE -ne 0) { throw "配方命令失败（$c）——原因见上方输出" }
                    }
                    & $Py (Join-Path $PSScriptRoot "ledger.py") remove --state-dir $StateDir --engine $code --host windows | Out-Null
                    if ($LASTEXITCODE -ne 0) { throw "装机账销账失败：$code" }
                } | Out-Null
            }
            Say "退池卸载段执行完毕（只删自装，账面已随卸毕销账）"
        } | Out-Null
        Run-Step "pool-remove" "退池留痕" { Say "机器移除审计留痕（退池侧命令回执见机群页）" } | Out-Null
    }
    "engines" {
        # 卸载账外件闸（T318 包④，与 install.sh apply_engine_recipe 同构）：判定全在
        # ledger.py uninstall-plan 单源，账外=用户手装件零触碰（§9.4-9 误删面=零）。
        $EngineAction = if ($Action) { $Action } else { "install" }
        $offLedger = $false
        if ($EngineAction -eq "uninstall" -and $Engine) {
            $uplan = ""
            try { $uplan = (& $Py (Join-Path $PSScriptRoot "ledger.py") uninstall-plan --state-dir $StateDir --host windows) -join "`n" }
            catch { Write-Error "卸载计划判定失败：$_"; $rc = 1 }
            $offLedger = ($rc -eq 0) -and (($uplan -split "`n" | ForEach-Object { ($_ -split "`t")[0] }) -notcontains $Engine)
            if ($offLedger) {
                Say "引擎「$Engine」账外=用户手装件——installkit 永不触碰（卸载只删自装；确要卸载请自行执行）"
            }
        }
        if (-not $offLedger) {
            Run-Step "fetch-recipe" "拉取配方" { Say "引擎安装配方经 api /internal 口拉取落盘（脚本禁读库；骨架位点）" } | Out-Null
            # T336 刀4 S4 前置链镜像（与 install.sh 同构）：仅 install 动作触发；uninstall 不需装件。
            if ($EngineAction -eq "install") {
                & $Py (Join-Path $PSScriptRoot "runtime_bootstrap.py") needs --state-dir $StateDir --answers-file (Join-Path $StateDir "answers-S1.json") 2>$null | Out-Null
                if ($LASTEXITCODE -eq 0) {
                    Run-Step "runtime-bootstrap:$Engine" "引擎运行时前置" {
                        Invoke-Py "runtime_bootstrap.py" @("ensure", "--yes", "--state-dir", $StateDir)
                    } | Out-Null
                }
            }
            Run-Step "apply-engine" "执行配方" { Say "S4 消费配方：探测/安装/卸载 + 装后重报 capabilities" } | Out-Null
        }
    }
    "repair" {
        # 体检位点实装（T318 包①）：调 doctor.py 单源件（与 install.sh 同构，§9.0 禁 PS 二套探测）；
        # 只读探测零副作用=不走 Run-Step 记账（重跑必重探）
        Say "体检报告（缺件=提示级点名非阻断）："
        Invoke-Py "doctor.py" @("--state-dir", $StateDir)
        # T336 刀3 缺件点名→自动补齐闭环镜像（与 install.sh 同构）：needs 闸判定单源
        # 在件内；repair=用户显式跑的修复命令，--yes 直接补齐（先补运行时再补装引擎件）。
        & $Py (Join-Path $PSScriptRoot "runtime_bootstrap.py") needs --state-dir $StateDir --answers-file (Join-Path $StateDir "answers-S1.json") 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) {
            Run-Step "runtime-ensure" "运行时自动补齐" {
                Invoke-Py "runtime_bootstrap.py" @("ensure", "--yes", "--state-dir", $StateDir)
            } | Out-Null
        }
        # 补段位点实装（T318 包②，与 install.sh 同构）：需求 diff 判定全在 ledger.py 单源，
        # PS 侧只消费计划并执行配方命令（recipe.py 单源取命令，禁二套 diff/解析）。
        # 行为级真装=Windows 宿主补账随三宿主矩阵（本机语法级实证，如实标注）。
        Run-Step "refill" "缺件补齐" {
            $plan = ""
            try { $plan = (& $Py (Join-Path $PSScriptRoot "ledger.py") refill-plan --state-dir $StateDir) -join "`n" }
            catch { throw "补装需求判定失败：$_" }
            if (-not $plan.Trim()) { Say "无可补：体检无缺件且装机账钉与配方一致（幂等不重复外呼）"; return }
            foreach ($line in ($plan -split "`n" | Where-Object { $_.Trim() })) {
                $code, $reason = $line -split "`t", 2
                $rf = Join-Path $StateDir "recipe-$code.json"
                if (-not (Test-Path $rf)) { Say "引擎「$code」补装跳过（$reason）——配方不在盘，请先跑 engines 场景拉配方"; continue }
                Say "补装引擎「$code」（$reason）"
                $cmds = (& $Py (Join-Path $PSScriptRoot "recipe.py") install --recipe-file $rf --host windows) -join "`n"
                if ($LASTEXITCODE -ne 0) { throw "配方命令取回失败：$cmds" }
                foreach ($c in ($cmds -split "`n" | Where-Object { $_.Trim() })) {
                    Say "执行配方命令：$c"
                    Invoke-Expression $c
                    if ($LASTEXITCODE -ne 0) { throw "配方命令失败（$c）——原因见上方输出" }
                }
                & $Py (Join-Path $PSScriptRoot "ledger.py") record --state-dir $StateDir --engine $code --host windows | Out-Null
                if ($LASTEXITCODE -ne 0) { throw "装机账记账失败：$code" }
            }
            Say "补装段执行完毕（装机账随装毕同步）"
        } | Out-Null
    }
}

Write-Log "=== scenario=$Scenario rc=$rc ==="
exit $rc
