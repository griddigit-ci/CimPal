<#
 Copyright (c) 2020-2026 gridDigIt Kft.
 Licensed under the EUPL-1.2-or-later.
 SPDX-License-Identifier: EUPL-1.2+
#>
<#
.SYNOPSIS
    Smoke-tests a CimPal CLI Docker image the way docs/cli/docker.md tells people to run it.

.DESCRIPTION
    Checks that:
      1. --version reports the expected release version.
      2. The image runs as UID 10001:10001, exposes no port, keeps its JAR and entrypoint
         read-only at runtime, and rejects the entrypoint's internal --cimpal-exec argument.
      3. A mapping validation of the docker-smoke fixture exits 1 with exactly one violation,
         prints only the JSON summary on stdout, and writes the Excel and Turtle reports to
         the bind mount (the Excel writer needs the fonts of the base image).
      4. CimPal's ~/.cimpal cache stays private: owned by the running UID and mode 700, in
         /home/cimpal for cimpal and in a private temporary home for any other UID.
      5. With --network none, a remote owl:imports fails closed: an error, never a clean pass.
      6. mcp answers initialize and tools/list (10 tools) over stdio with nothing but JSON-RPC
         on stdout, also with USE_SYSTEM_CA_CERTS=1 and an extra certificate mounted at
         /certificates.
      7. serve answers GET /health on a loopback-published port and stops on `docker stop`.

    On Linux the runs that write to the bind mount use --user <uid>:<gid> of the caller, as the
    docs advise. The work folder is CimPal-CLI/target/docker-smoke. Used by the "Docker image"
    job in ci.yml and before the push in release.yml.

.EXAMPLE
    ./scripts/Test-DockerImage.ps1 -Image cimpal:dev
    ./scripts/Test-DockerImage.ps1 -Image ghcr.io/griddigit-ci/cimpal:2026.10.1.1 -ExpectedVersion 2026.10.1.1
#>
param(
    [Parameter(Mandatory)] [string]$Image,
    # Defaults to <cimpal.version> in the root pom.xml.
    [string]$ExpectedVersion
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false

$repoRoot = Split-Path -Parent $PSScriptRoot
if (-not $ExpectedVersion) {
    $rootPom = Get-Content (Join-Path $repoRoot 'pom.xml') -Raw
    if ($rootPom -notmatch '<cimpal\.version>([^<]+)</cimpal\.version>') {
        throw "Could not find <cimpal.version> in the root pom.xml"
    }
    $ExpectedVersion = $Matches[1]
}

$failures = [System.Collections.Generic.List[string]]::new()

function Check([string]$Name, [bool]$Ok, [string]$Detail = '') {
    if ($Ok) {
        Write-Host "PASS  $Name"
    } else {
        Write-Host "FAIL  $Name  $Detail"
        $failures.Add($Name)
    }
}

# Runs docker; returns the exit code, the stdout lines and the stderr text.
function Invoke-Docker([string[]]$Arguments, [string]$Stdin) {
    $out = [System.IO.Path]::GetTempFileName()
    $err = [System.IO.Path]::GetTempFileName()
    try {
        if ($PSBoundParameters.ContainsKey('Stdin')) {
            $Stdin | & docker @Arguments 1> $out 2> $err
        } else {
            & docker @Arguments 1> $out 2> $err
        }
        [pscustomobject]@{
            ExitCode = $LASTEXITCODE
            Stdout   = @(Get-Content $out)
            Stderr   = (Get-Content $err -Raw) ?? ''
        }
    } finally {
        Remove-Item $out, $err -ErrorAction SilentlyContinue
    }
}

$mcpRequests = @(
    '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"cimpal-docker-smoke","version":"1"}}}'
    '{"jsonrpc":"2.0","method":"notifications/initialized"}'
    '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
) -join "`n"

function Test-Mcp([string]$Label, [string[]]$ExtraArguments) {
    $r = Invoke-Docker (@('run', '-i', '--rm') + $ExtraArguments + @($Image, 'mcp')) -Stdin $mcpRequests
    $messages = @()
    $allJson = $true
    foreach ($line in $r.Stdout) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        try { $messages += ($line | ConvertFrom-Json) } catch { $allJson = $false }
    }
    $init = $messages | Where-Object { $_.id -eq 1 }
    $list = $messages | Where-Object { $_.id -eq 2 }
    Check "mcp$Label exits 0 at the end of its input" ($r.ExitCode -eq 0) "exit=$($r.ExitCode) stderr=$($r.Stderr)"
    Check "mcp$Label writes only JSON-RPC to stdout" ($allJson -and $messages.Count -ge 2) "stdout=$($r.Stdout -join ' | ')"
    Check "mcp$Label initialize reports the release version" ($init.result.serverInfo.version -eq $ExpectedVersion) `
        "serverInfo=$($init.result.serverInfo | ConvertTo-Json -Compress)"
    Check "mcp$Label tools/list returns 10 tools" (@($list.result.tools).Count -eq 10) "tools=$(@($list.result.tools).Count)"
    return $r
}

# A mapping validation of the fixture, run through the real entrypoint by sh, which then reports
# where the JVM put the ~/.cimpal cache and who owns it. Outputs stay inside the container.
$cacheProbe = 'cd /data && /opt/cimpal/entrypoint.sh validate --workflow mapping --mapping-csv /data/mapping.csv ' +
    '--models /data/models --constraints-root /data/constraints --output /tmp/out ' +
    '--xml-base http://example.com/data --workers 1 >/dev/null 2>/tmp/err; ' +
    'echo "warnings $(grep -c "Could not create remote XML cache dir" /tmp/err)"; ' +
    'for d in /home/cimpal/.cimpal/remote-xml-cache /tmp/tmp.*/.cimpal/remote-xml-cache; do ' +
    '[ -d "$d" ] && stat -c "cache %a %u %n" "$d" && stat -c "home %a %u %n" "${d%/.cimpal/remote-xml-cache}"; done; ' +
    'stat -c "imagehome %a %u %n" /home/cimpal'

function Test-CachePrivacy([string]$Label, [string[]]$UserArguments, [string]$Uid, [string]$HomePattern) {
    $r = Invoke-Docker (@('run', '--rm') + $UserArguments + @('--mount', "type=bind,source=$work,target=/data,readonly",
        '--entrypoint', 'sh', $Image, '-c', $cacheProbe))
    $cache = @($r.Stdout | Where-Object { $_ -like 'cache *' })
    $homeLine = @($r.Stdout | Where-Object { $_ -like 'home *' })
    $imageHome = @($r.Stdout | Where-Object { $_ -like 'imagehome *' })
    $output = $r.Stdout -join ' | '
    Check "cache ($Label): one cache directory, mode 700, owned by UID $Uid" `
        (($cache.Count -eq 1) -and ($cache[0] -like "cache 700 $Uid *")) "stdout=$output"
    Check "cache ($Label): home is $HomePattern, mode 700 or 750, owned by UID $Uid" `
        (($homeLine.Count -eq 1) -and ($homeLine[0] -match "^home (700|750) $Uid $HomePattern$")) "stdout=$output"
    $imageHomeMode = if ($imageHome.Count -eq 1) { [Convert]::ToInt32($imageHome[0].Split(' ')[1], 8) } else { -1 }
    Check "cache ($Label): /home/cimpal is not world-writable" (($imageHomeMode -ge 0) -and (($imageHomeMode -band 2) -eq 0)) "stdout=$output"
    Check "cache ($Label): no cache-directory warning" ($r.Stdout -contains 'warnings 0') "stdout=$output stderr=$($r.Stderr)"
}

Write-Host "Smoke-testing $Image, expecting CimPal CLI $ExpectedVersion"
$userArguments = @()
if ($IsLinux) {
    $userArguments = @('--user', "$(id -u):$(id -g)")
}

$work = Join-Path $repoRoot 'CimPal-CLI/target/docker-smoke'
Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $work | Out-Null
$container = $null
try {
    Copy-Item -Recurse (Join-Path $repoRoot 'CimPal-CLI/src/test/resources/fixtures/docker-smoke/*') $work
    $dataMount = "type=bind,source=$work,target=/data"

    # 1. Version
    $r = Invoke-Docker @('run', '--rm', $Image, '--version')
    Check '--version reports the release version' (($r.ExitCode -eq 0) -and (($r.Stdout -join "`n") -eq "CimPal CLI $ExpectedVersion")) `
        "exit=$($r.ExitCode) stdout=$($r.Stdout -join ' | ')"

    # 2. Image configuration
    $r = Invoke-Docker @('image', 'inspect', '-f', '{{.Config.User}} {{json .Config.ExposedPorts}}', $Image)
    Check 'the image user is numeric 10001:10001' (($r.Stdout -join '') -like '10001:10001 *') "inspect=$($r.Stdout -join ' | ')"
    Check 'the image exposes no port (so -P cannot publish serve)' (($r.Stdout -join '') -like '* null') "inspect=$($r.Stdout -join ' | ')"
    $r = Invoke-Docker @('run', '--rm', '--entrypoint', 'id', $Image)
    Check 'runs as UID 10001 (cimpal)' (($r.Stdout -join '') -like 'uid=10001(cimpal) gid=10001(cimpal)*') "stdout=$($r.Stdout -join ' | ')"
    $readOnly = 'test ! -w /opt/cimpal && test ! -w /opt/cimpal/CimPal-CLI.jar && test ! -w /opt/cimpal/entrypoint.sh && echo read-only'
    foreach ($u in @(@(), @('--user', '12345:12345'))) {
        $who = if ($u.Count) { 'UID 12345' } else { 'cimpal' }
        $r = Invoke-Docker (@('run', '--rm') + $u + @('--entrypoint', 'sh', $Image, '-c', $readOnly))
        Check "the JAR and entrypoint are read-only for $who" (($r.Stdout -join '') -eq 'read-only') "stdout=$($r.Stdout -join ' | ')"
    }
    $r = Invoke-Docker @('run', '--rm', $Image, '--cimpal-exec', '--version')
    Check 'the internal --cimpal-exec argument is rejected' ($r.ExitCode -ne 0) "exit=$($r.ExitCode) stdout=$($r.Stdout -join ' | ')"

    # 3. Mapping validation against the bind mount
    $r = Invoke-Docker (@('run', '--rm', '--mount', $dataMount) + $userArguments + @($Image,
        'validate', '--workflow', 'mapping', '--mapping-csv', '/data/mapping.csv', '--models', '/data/models',
        '--constraints-root', '/data/constraints', '--output', '/data/out', '--xml-base', 'http://example.com/data',
        '--workers', '1', '--format', 'json', '--samples', '3'))
    $summary = $null
    try { $summary = ($r.Stdout -join "`n") | ConvertFrom-Json } catch { }
    Check 'validate exits 1 (violations found)' ($r.ExitCode -eq 1) "exit=$($r.ExitCode) stderr=$($r.Stderr)"
    Check 'validate prints only the JSON summary to stdout' (($null -ne $summary) -and ($summary.schema -like 'cimpal-validate-summary/*')) `
        "stdout=$($r.Stdout -join ' | ')"
    Check 'validate counts exactly one violation' (($summary.totals.violations -eq 1) -and ($summary.totals.errors -eq 0)) `
        "totals=$($summary.totals | ConvertTo-Json -Compress)"
    $workbooks = @(Get-ChildItem (Join-Path $work 'out') -Filter '*.xlsx' -Recurse -ErrorAction SilentlyContinue)
    Check 'validate writes the Excel report to the bind mount' (($workbooks.Count -ge 1) -and ($workbooks[0].Length -gt 0))
    $turtle = @(Get-ChildItem (Join-Path $work 'out') -Filter '*__report.ttl' -Recurse -ErrorAction SilentlyContinue)
    Check 'validate writes the Turtle report (--samples)' ($turtle.Count -ge 1)
    Check 'the cache directory can be created' ($r.Stderr -notmatch 'Could not create remote XML cache dir') "stderr=$($r.Stderr)"

    # 4. Cache privacy, for the image user and for an arbitrary UID
    Test-CachePrivacy 'cimpal' @() '10001' '/home/cimpal'
    Test-CachePrivacy 'UID 12345' @('--user', '12345:12345') '12345' '/tmp/tmp\.\w+'

    # 5. Offline: a remote owl:imports must fail closed
    $r = Invoke-Docker @('run', '--rm', '--network', 'none', '--mount', "type=bind,source=$work,target=/data,readonly", $Image,
        'validate', '--workflow', 'mapping', '--mapping-csv', '/data/mapping-offline.csv', '--models', '/data/models',
        '--constraints-root', '/data/constraints', '--output', '/tmp/out', '--xml-base', 'http://example.com/data',
        '--workers', '1', '--format', 'json', '--samples', '0')
    $offline = $null
    try { $offline = ($r.Stdout -join "`n") | ConvertFrom-Json } catch { }
    Check 'offline: an unreachable owl:imports is an error, not a clean pass' `
        (($r.ExitCode -eq 1) -and ($offline.totals.errors -ge 1) -and ($offline.totals.conforming -eq 0)) `
        "exit=$($r.ExitCode) totals=$($offline.totals | ConvertTo-Json -Compress)"

    # 6. MCP over stdio, plain and with the CA-certificate hook of the base image
    Test-Mcp '' @() | Out-Null

    $certificates = Join-Path $work 'certificates'
    New-Item -ItemType Directory -Path $certificates | Out-Null
    $r = Invoke-Docker (@('run', '--rm', '--mount', "type=bind,source=$certificates,target=/out") + $userArguments + @(
        '--entrypoint', 'openssl', $Image, 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', '/tmp/smoke.key',
        '-out', '/out/smoke.crt', '-subj', '/CN=cimpal-docker-smoke', '-days', '1'))
    Check 'a throwaway CA certificate is generated' (($r.ExitCode -eq 0) -and (Test-Path (Join-Path $certificates 'smoke.crt'))) `
        "exit=$($r.ExitCode) stderr=$($r.Stderr)"
    $r = Test-Mcp ' with USE_SYSTEM_CA_CERTS' @('-e', 'USE_SYSTEM_CA_CERTS=1',
        '--mount', "type=bind,source=$certificates,target=/certificates,readonly")
    Check 'the CA hook imports the mounted certificate and reports it on stderr' `
        ($r.Stderr -match 'Adding certificate with alias cimpal-docker-smoke') "stderr=$($r.Stderr)"

    # 7. serve, published on the host loopback only
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $listener.Start()
    $port = $listener.LocalEndpoint.Port
    $listener.Stop()
    $container = (& docker run -d --rm -p "127.0.0.1:${port}:7474" $Image serve --host 0.0.0.0 | Out-String).Trim()
    Check 'serve starts in the background' ($LASTEXITCODE -eq 0) "container=$container"
    $health = $null
    $deadline = (Get-Date).AddSeconds(60)
    while (-not $health -and (Get-Date) -lt $deadline) {
        try {
            $health = Invoke-RestMethod -Uri "http://127.0.0.1:$port/health" -TimeoutSec 5
        } catch {
            Start-Sleep -Milliseconds 500
        }
    }
    Check 'serve answers GET /health on the published port' ($health.status -eq 'ok') "health=$($health | ConvertTo-Json -Compress)"
    Check 'serve /health reports the release version' ($health.version -eq "CimPal CLI $ExpectedVersion") `
        "health=$($health | ConvertTo-Json -Compress)"
    $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    & docker stop --time 20 $container | Out-Null
    $stopwatch.Stop()
    Check 'serve stops on docker stop (SIGTERM, no kill)' (($LASTEXITCODE -eq 0) -and ($stopwatch.Elapsed.TotalSeconds -lt 15)) `
        "took $([int]$stopwatch.Elapsed.TotalSeconds) s"
} finally {
    # Never leave an unauthenticated serve behind, even when a check above threw.
    if ($container) {
        & docker rm -f $container 2>&1 | Out-Null
    }
    Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
}

if ($failures.Count -gt 0) {
    Write-Host ""
    Write-Host "$($failures.Count) check(s) failed: $($failures -join '; ')"
    exit 1
}
Write-Host ""
Write-Host "All checks passed for $Image (CimPal CLI $ExpectedVersion)."
