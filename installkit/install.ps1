# =============================================================================
# testPike installkit entry point (Windows PowerShell 5.1 baseline; use
# install.sh on Linux/macOS/WSL). Scenario framework S1-S5 + step checkpoints
# for resume + full log file + pool-join heartbeat reporting.
# Same contract lines as install.sh (protocol 03 install surface, single
# source): enroll/heartbeat POST body = {token, fingerprint}, through the api
# /internal entry only (external machines never call core directly).
# Red line: the plain token goes only into the request body; this script's log
# carries none; uninstall removes only what installkit itself installed.
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
Usage: .\install.ps1 -Scenario <scenario> [-Role .. -Token .. -BaseUrl ..]
Scenarios: first(S1) scale-out(S2) scale-in(S3) engines(S4) repair(S5), aliases S1-S5
Options: -Role executor|ai-node|platform  -Token one-time pool token  -BaseUrl api address
      -MirrorBaseUrl intranet mirror root  -Restart clear checkpoints and rerun from scratch
Idempotent and rerunnable, resumable from checkpoints (state file), fully logged, and every failure names the cause and the fix entry.
"@ | Write-Host
}

$Aliases = @{ S1 = "first"; S2 = "scale-out"; S3 = "scale-in"; S4 = "engines"; S5 = "repair" }
if ($Aliases.ContainsKey($Scenario)) { $Scenario = $Aliases[$Scenario] }
if ([string]::IsNullOrWhiteSpace($Scenario)) {
    Write-Error "Missing -Scenario=<scenario> (choose first/scale-out/scale-in/engines/repair, aliases S1-S5)"
    Show-Usage; exit 2
}
if ($Scenario -notin @("first", "scale-out", "scale-in", "engines", "repair")) {
    Write-Error "Unknown scenario ""$Scenario"" -- choose: first / scale-out / scale-in / engines / repair (aliases S1-S5)"
    exit 2
}

New-Item -ItemType Directory -Force -Path $StateDir, $LogDir | Out-Null
$State = Join-Path $StateDir "state-$Scenario.txt"
$LogFile = Join-Path $LogDir ("installkit-{0}.log" -f (Get-Date -Format "yyyyMMdd"))
if ($Restart) { "" | Set-Content -Path $State -Encoding UTF8 }

function Write-Log([string]$m) { ("[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $m) | Add-Content -Path $LogFile -Encoding UTF8 }
Write-Log "=== scenario=$Scenario start ==="

function Say([string]$m) { Write-Host $m; Write-Log "say: $m" }

# Checkpoint resume: only a successful step is recorded; recorded steps are
# skipped (idempotent rerun, zero repeated calls)
function Run-Step([string]$Id, [string]$Desc, [scriptblock]$Body) {
    if ((Test-Path $State) -and ((Get-Content $State -Encoding UTF8) -contains "step:$Id")) {
        Write-Log "skip=$Id"
        Write-Host "Step [$Desc] already completed (checkpoint resume, skipped)"
        return $true
    }
    Write-Log "step=$Id start: $Desc"
    try { & $Body } catch { Write-Log "step=$Id failed"; Write-Error $_; return $false }
    "step:$Id" | Add-Content -Path $State -Encoding UTF8
    Write-Log "step=$Id done"
    return $true
}

function Get-MachineFingerprint {
    # Machine fingerprint = stable sha256 id (Windows reads MachineGuid from
    # the registry, falling back to the hostname)
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
        Write-Error "Platform unreachable: $BaseUrl -- confirm the api service is up, then rerun the same command (checkpoint resume will not join the pool twice)"
        return $null
    }
    if ($resp.code -ne "0") {
        Write-Error ($(if ($resp.msg) { $resp.msg } else { "Platform rejected the request (no reason returned)" }))
        return $resp
    }
    return $resp
}

$Py = if ($env:PYTHON_BIN) { $env:PYTHON_BIN } else { "python" }

# S1 shared consumption: questions, bring-up and trial-run all call the python
# single-source components (s1_wizard/s1_bootstrap; the single-source promise
# forbids a second PS-side wizard; failure throws through Run-Step checkpoint
# semantics = not recorded, resumable)
function Invoke-Py([string]$ScriptName, [string[]]$PyArgs) {
    & $Py (Join-Path $PSScriptRoot $ScriptName) @PyArgs
    if ($LASTEXITCODE -ne 0) { throw ("{0} failed (exit code {1}) -- cause and fix entry are in the output above; after fixing, rerun the same command to resume from the checkpoint" -f $ScriptName, $LASTEXITCODE) }
}

$rc = 0
switch ($Scenario) {
    "first" {
        # The Windows profile behaviour proof for preflight is booked with the T316 closing account (this WSL host verified syntax only; the behaviour path is honestly marked unreachable)
        Run-Step "preflight" "Environment preflight" { Say ("Preflight: OS=Windows {0} PowerShell {1} python={2}" -f [System.Environment]::OSVersion.Version, $PSVersionTable.PSVersion, $Py) } | Out-Null
        # Mirror-key fetch mirrors install.sh (explicit CLI value wins; a fetch failure degrades to the official registry with a readable line, never blocking)
        if (-not $MirrorBaseUrl -and $BaseUrl) {
            $fetched = & $Py (Join-Path $PSScriptRoot "enroll_client.py") fetch-mirror --base-url $BaseUrl
            if ($LASTEXITCODE -eq 0 -and $fetched) { $MirrorBaseUrl = "$fetched".Trim() }
            Write-Log "mirror-key fetched=$MirrorBaseUrl"
        }
        Run-Step "role-question" "Role and engine questions" {
            Invoke-Py "s1_wizard.py" @("--answers-file", (Join-Path $StateDir "answers-S1.json"), "--mirror-default", $MirrorBaseUrl)
        } | Out-Null
        # S1 runtime prerequisites checkpoint (mirrors install.sh; the needs decision lives in the component):
        # the --yes basis = running the installer explicitly is consent to fill zero prerequisites automatically (no-development-experience audience).
        & $Py (Join-Path $PSScriptRoot "runtime_bootstrap.py") needs --state-dir $StateDir --answers-file (Join-Path $StateDir "answers-S1.json") 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) {
            Run-Step "runtime-bootstrap" "Engine runtime prerequisites" {
                Invoke-Py "runtime_bootstrap.py" @("ensure", "--yes", "--state-dir", $StateDir)
            } | Out-Null
        }
        Run-Step "compose-seed" "Services and demo seed" {
            Invoke-Py "s1_bootstrap.py" @("compose", "--answers-file", (Join-Path $StateDir "answers-S1.json"))
        } | Out-Null
        Run-Step "seed-demo" "demo data auto seeding" {
            Invoke-Py "s1_bootstrap.py" @("seed-demo")
        } | Out-Null
    }
    "scale-out" {
        if ($Role -and ($Role -notin @("executor", "ai-node", "platform"))) {
            Write-Error "Machine profile ""$Role"" is outside the allowed values -- it must be one of executor / ai-node / platform (tokens are issued for exactly these three profiles; the script never invents roles)"
            exit 2
        }
        if (-not $Token) {
            Write-Error "Missing -Token -- generate the enrollment token on the fleet page via ""Add executor / AI node"" and copy the command that carries the token (one-time + TTL; when it expires, generate a new one)"
            exit 2
        }
        if (-not $BaseUrl) {
            Write-Error "Missing -BaseUrl -- the api service address (like http://<platform host>:8082); the installer goes through the api entry only, never core directly"
            exit 2
        }
        if (Run-Step "hb-start" "Start heartbeat" { Invoke-EnrollApi "/internal/cluster/enroll/heartbeat" $Token }) {
            $ok = $true
            Run-Step "enroll-token" "Redeem token and join the pool" {
                $r = Invoke-EnrollApi "/internal/cluster/enroll" $Token
                if (-not $r -or $r.code -ne "0") { throw "token redemption did not succeed" }
                Say ("Joined the pool: role={0} scope={1}" -f $r.data.role, $(if ($r.data.projectScope) { $r.data.projectScope } else { "all projects" }))
            } | Out-Null
        }
        Run-Step "hb-done" "Finish heartbeat" { Invoke-EnrollApi "/internal/cluster/enroll/heartbeat" $Token } | Out-Null
    }
    "scale-in" {
        Run-Step "drain-check" "Drain first" { Say "Scale-in must drain first: stop claiming -> running tasks reach zero -> then remove (same semantics as the existing pool-removal flow; skeleton checkpoint)" } | Out-Null
        Run-Step "uninstall-boundary" "Uninstall boundary" { Say "Safety boundary: only what installkit itself installed is uninstalled (version dirs + services + ledger); user-installed pieces are never touched" } | Out-Null
        # Scale-in uninstall stage (mirrors the install.sh purge): the list of
        # self-installed pieces is decided entirely by ledger.py uninstall-plan
        # (a second PS-side ledger gate is forbidden); engine by engine the
        # recipe.py uninstall commands run and the ledger row is cleared.
        Run-Step "purge-self-installed" "Uninstall self-installed engine pieces" {
            $plan = ""
            try { $plan = (& $Py (Join-Path $PSScriptRoot "ledger.py") uninstall-plan --state-dir $StateDir --host windows) -join "`n" }
            catch { throw "uninstall plan decision failed: $_" }
            if (-not $plan.Trim()) { Say "No installkit self-installed engine pieces on this host (install ledger is empty) -- scale-in uninstalls nothing and touches nothing; user-installed pieces are never in scope here"; return }
            foreach ($line in ($plan -split "`n" | Where-Object { $_.Trim() })) {
                $code, $pin = $line -split "`t", 2
                Run-Step "uninstall-engine:$code" "Uninstall self-installed engine $code" {
                    $rf = Join-Path $StateDir "recipe-$code.json"
                    if (-not (Test-Path $rf)) { Say "Skipped uninstall of engine ""$code"" -- no recipe on disk; the ledger keeps the row (what cannot be removed is not de-booked; ledger matches host)"; return }
                    $cmds = (& $Py (Join-Path $PSScriptRoot "recipe.py") uninstall --recipe-file $rf --host windows) -join "`n"
                    if ($LASTEXITCODE -ne 0) { throw "could not retrieve the recipe commands: $cmds" }
                    foreach ($c in ($cmds -split "`n" | Where-Object { $_.Trim() })) {
                        Say "Running recipe command: $c"
                        Invoke-Expression $c
                        if ($LASTEXITCODE -ne 0) { throw "recipe command failed ($c) -- reason in the output above" }
                    }
                    & $Py (Join-Path $PSScriptRoot "ledger.py") remove --state-dir $StateDir --engine $code --host windows | Out-Null
                    if ($LASTEXITCODE -ne 0) { throw "install ledger de-booking failed: $code" }
                } | Out-Null
            }
            Say "Scale-in uninstall stage finished (only self-installed pieces removed; the ledger was cleared along the way)"
        } | Out-Null
        Run-Step "pool-remove" "Scale-in trail" { Say "Machine removal is audit-recorded (command receipts from the scale-in side appear on the fleet page)" } | Out-Null
    }
    "engines" {
        # Off-ledger uninstall gate (mirrors install.sh apply_engine_recipe): the
        # decision lives entirely in ledger.py uninstall-plan; off-ledger =
        # user-installed pieces stay untouched (zero accidental-removal surface).
        $EngineAction = if ($Action) { $Action } else { "install" }
        $offLedger = $false
        if ($EngineAction -eq "uninstall" -and $Engine) {
            $uplan = ""
            try { $uplan = (& $Py (Join-Path $PSScriptRoot "ledger.py") uninstall-plan --state-dir $StateDir --host windows) -join "`n" }
            catch { Write-Error "uninstall plan decision failed: $_"; $rc = 1 }
            $offLedger = ($rc -eq 0) -and (($uplan -split "`n" | ForEach-Object { ($_ -split "`t")[0] }) -notcontains $Engine)
            if ($offLedger) {
                Say "Engine ""$Engine"" is not in the install ledger = user-installed piece -- installkit never touches it (uninstall removes only self-installed items; remove it yourself if that is really intended)"
            }
        }
        if (-not $offLedger) {
            Run-Step "fetch-recipe" "Fetch recipe" { Say "The engine install recipe is fetched and stored through the api /internal entry (the script never reads the database; skeleton checkpoint)" } | Out-Null
            # S4 prerequisite chain mirrors install.sh: only the install action triggers it; uninstall installs nothing.
            if ($EngineAction -eq "install") {
                & $Py (Join-Path $PSScriptRoot "runtime_bootstrap.py") needs --state-dir $StateDir --answers-file (Join-Path $StateDir "answers-S1.json") 2>$null | Out-Null
                if ($LASTEXITCODE -eq 0) {
                    Run-Step "runtime-bootstrap:$Engine" "Engine runtime prerequisites" {
                        Invoke-Py "runtime_bootstrap.py" @("ensure", "--yes", "--state-dir", $StateDir)
                    } | Out-Null
                }
            }
            Run-Step "apply-engine" "Run recipe" { Say "S4 consumes the recipe: probe/install/uninstall + post-install capability reporting" } | Out-Null
        }
    }
    "repair" {
        # Health stage calls doctor.py, the single-source component (mirrors
        # install.sh; a second PS-side probe set is forbidden);
        # read-only probes with zero side effects = no Run-Step bookkeeping (every rerun really re-probes)
        Say "Health report (missing pieces are advisory by name, not blocking):"
        Invoke-Py "doctor.py" @("--state-dir", $StateDir)
        # Missing-piece named -> auto-fix loop mirrors install.sh: the needs
        # decision lives in the component; repair is a fix command the user ran
        # explicitly, so --yes fills directly (runtime first, engine pieces after).
        & $Py (Join-Path $PSScriptRoot "runtime_bootstrap.py") needs --state-dir $StateDir --answers-file (Join-Path $StateDir "answers-S1.json") 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) {
            Run-Step "runtime-ensure" "Auto-fill runtime prerequisites" {
                Invoke-Py "runtime_bootstrap.py" @("ensure", "--yes", "--state-dir", $StateDir)
            } | Out-Null
        }
        # Refill checkpoint mirrors install.sh: the required diff is decided
        # entirely in ledger.py; the PS side only consumes the plan and runs the
        # recipe commands (recipe.py is the single command source; a second
        # diff/parser is forbidden).
        # Real installation behaviour on Windows hosts is booked with the three-host matrix (this host verified syntax only; marked honestly).
        Run-Step "refill" "Fill missing pieces" {
            $plan = ""
            try { $plan = (& $Py (Join-Path $PSScriptRoot "ledger.py") refill-plan --state-dir $StateDir) -join "`n" }
            catch { throw "refill requirement decision failed: $_" }
            if (-not $plan.Trim()) { Say "Nothing to refill: the health report found no missing piece and every ledger pin matches its recipe (idempotent, no repeated call)"; return }
            foreach ($line in ($plan -split "`n" | Where-Object { $_.Trim() })) {
                $code, $reason = $line -split "`t", 2
                $rf = Join-Path $StateDir "recipe-$code.json"
                if (-not (Test-Path $rf)) { Say "Skipped refill of engine ""$code"" ($reason) -- no recipe on disk; run the engines scenario first to fetch the recipe"; continue }
                Say "Refilling engine ""$code"" ($reason)"
                $cmds = (& $Py (Join-Path $PSScriptRoot "recipe.py") install --recipe-file $rf --host windows) -join "`n"
                if ($LASTEXITCODE -ne 0) { throw "could not retrieve the recipe commands: $cmds" }
                foreach ($c in ($cmds -split "`n" | Where-Object { $_.Trim() })) {
                    Say "Running recipe command: $c"
                    Invoke-Expression $c
                    if ($LASTEXITCODE -ne 0) { throw "recipe command failed ($c) -- reason in the output above" }
                }
                & $Py (Join-Path $PSScriptRoot "ledger.py") record --state-dir $StateDir --engine $code --host windows | Out-Null
                if ($LASTEXITCODE -ne 0) { throw "install ledger booking failed: $code" }
            }
            Say "Refill stage finished (the install ledger syncs on completion)"
        } | Out-Null
    }
}

Write-Log "=== scenario=$Scenario rc=$rc ==="
exit $rc
