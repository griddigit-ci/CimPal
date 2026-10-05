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
      7. serve runs as the docs say (--allow-remote, token from CIMPAL_API_TOKEN, same port inside
         and outside on the host loopback): /health answers, a remapped port gets 403, a request
         without the token 401, and POST /shutdown with the token stops the container.
      8. JVM defaults: MaxRAMPercentage=75 and ExitOnOutOfMemoryError are set, JAVA_OPTS overrides
         them, VM output goes to stderr, and an out-of-memory run ends with exit 3.
      9. The OCI version label matches the expected version and the revision label is set.

    Validation, mcp and serve run with a read-only root filesystem and a tmpfs /tmp
    (--read-only --tmpfs /tmp), as docs/cli/docker.md recommends; without the tmpfs the entrypoint
    stops with exit 2 and says why. On Linux the runs that write to the bind mount use
    --user <uid>:<gid> of the caller, as the docs advise. The work folder is
    CimPal-CLI/target/docker-smoke. Used by the "Docker image" job in ci.yml and before the push in
    release.yml. Build the image with the CIMPAL_VERSION and GIT_SHA build arguments (see the docs).

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

function Get-FreePort {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $listener.Start()
    $port = $listener.LocalEndpoint.Port
    $listener.Stop()
    return $port
}

# HTTP status of a request, also for 4xx answers.
function Get-StatusCode([string]$Uri, [hashtable]$Headers = @{}, [string]$Method = 'GET', [string]$Body) {
    try {
        $arguments = @{ Uri = $Uri; Method = $Method; Headers = $Headers; TimeoutSec = 10; SkipHttpErrorCheck = $true }
        if ($Body) { $arguments.Body = $Body; $arguments.ContentType = 'application/json' }
        return (Invoke-WebRequest @arguments).StatusCode
    } catch {
        return "error: $($_.Exception.Message)"
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
# A read-only root filesystem with a writable /tmp, as the docs recommend (Kubernetes:
# readOnlyRootFilesystem plus an emptyDir at /tmp).
$readOnlyFs = @('--read-only', '--tmpfs', '/tmp')

$work = Join-Path $repoRoot 'CimPal-CLI/target/docker-smoke'
Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $work | Out-Null
$container = $null
$callerToken = $env:CIMPAL_API_TOKEN
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
    $readOnly = 'test ! -w /opt/cimpal && test ! -w /opt/cimpal/CimPal-CLI.jar && test ! -w /opt/cimpal/entrypoint.sh ' +
        '&& test ! -w /opt/cimpal/configs && echo read-only'
    foreach ($u in @(@(), @('--user', '12345:12345'))) {
        $who = if ($u.Count) { 'UID 12345' } else { 'cimpal' }
        $r = Invoke-Docker (@('run', '--rm') + $u + @('--entrypoint', 'sh', $Image, '-c', $readOnly))
        Check "the JAR and entrypoint are read-only for $who" (($r.Stdout -join '') -eq 'read-only') "stdout=$($r.Stdout -join ' | ')"
    }
    $r = Invoke-Docker @('run', '--rm', $Image, '--cimpal-exec', '--version')
    Check 'the internal --cimpal-exec argument is rejected' ($r.ExitCode -ne 0) "exit=$($r.ExitCode) stdout=$($r.Stdout -join ' | ')"
    $r = Invoke-Docker @('run', '--rm', '--entrypoint', 'sh', $Image, '-c',
        'test -s /opt/cimpal/configs/validate-mapping-cgmes30.json && test -s /opt/cimpal/LICENSE.md && test -s /opt/cimpal/eupl_v1.2_en.pdf && echo present')
    Check 'the example configs and the licence files are in /opt/cimpal' (($r.Stdout -join '') -eq 'present') "stdout=$($r.Stdout -join ' | ')"
    $r = Invoke-Docker @('run', '--rm', '--read-only', $Image, '--version')
    Check 'with --read-only but no writable /tmp the entrypoint stops with exit 2 and names --tmpfs /tmp' `
        (($r.ExitCode -eq 2) -and ($r.Stderr -match '--tmpfs /tmp') -and ($r.Stdout.Count -eq 0)) `
        "exit=$($r.ExitCode) stdout=$($r.Stdout -join ' | ') stderr=$($r.Stderr)"

    # 3. Mapping validation against the bind mount, with a read-only root filesystem
    $r = Invoke-Docker (@('run', '--rm', '--mount', $dataMount) + $readOnlyFs + $userArguments + @($Image,
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
    # A read-only root filesystem: /home/cimpal can't be written, so cimpal gets a home in /tmp too.
    Test-CachePrivacy 'cimpal, read-only root' $readOnlyFs '10001' '/tmp/tmp\.\w+'
    Test-CachePrivacy 'UID 12345, read-only root' ($readOnlyFs + @('--user', '12345:12345')) '12345' '/tmp/tmp\.\w+'
    # The image sets -Duser.home after JAVA_OPTS and unsets LOCALAPPDATA, so neither can move the cache.
    Test-CachePrivacy 'UID 12345, user.home and LOCALAPPDATA overridden' @('--user', '12345:12345',
        '-e', 'JAVA_OPTS=-Duser.home=/home/cimpal', '-e', 'LOCALAPPDATA=/home/cimpal') '12345' '/tmp/tmp\.\w+'

    # 5. Offline: a remote owl:imports must fail closed
    $r = Invoke-Docker (@('run', '--rm', '--network', 'none', '--mount', "type=bind,source=$work,target=/data,readonly") + $readOnlyFs + @($Image,
        'validate', '--workflow', 'mapping', '--mapping-csv', '/data/mapping-offline.csv', '--models', '/data/models',
        '--constraints-root', '/data/constraints', '--output', '/tmp/out', '--xml-base', 'http://example.com/data',
        '--workers', '1', '--format', 'json', '--samples', '0'))
    $offline = $null
    try { $offline = ($r.Stdout -join "`n") | ConvertFrom-Json } catch { }
    Check 'offline: an unreachable owl:imports is an error, not a clean pass' `
        (($r.ExitCode -eq 1) -and ($offline.totals.errors -ge 1) -and ($offline.totals.conforming -eq 0)) `
        "exit=$($r.ExitCode) totals=$($offline.totals | ConvertTo-Json -Compress)"

    # 6. MCP over stdio, plain and with the CA-certificate hook of the base image
    Test-Mcp '' $readOnlyFs | Out-Null

    $certificates = Join-Path $work 'certificates'
    New-Item -ItemType Directory -Path $certificates | Out-Null
    $r = Invoke-Docker (@('run', '--rm', '--mount', "type=bind,source=$certificates,target=/out") + $userArguments + @(
        '--entrypoint', 'openssl', $Image, 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', '/tmp/smoke.key',
        '-out', '/out/smoke.crt', '-subj', '/CN=cimpal-docker-smoke', '-days', '1'))
    Check 'a throwaway CA certificate is generated' (($r.ExitCode -eq 0) -and (Test-Path (Join-Path $certificates 'smoke.crt'))) `
        "exit=$($r.ExitCode) stderr=$($r.Stderr)"
    $r = Test-Mcp ' with USE_SYSTEM_CA_CERTS' ($readOnlyFs + @('-e', 'USE_SYSTEM_CA_CERTS=1',
        '--mount', "type=bind,source=$certificates,target=/certificates,readonly"))
    Check 'the CA hook imports the mounted certificate and reports it on stderr' `
        ($r.Stderr -match 'Adding certificate with alias cimpal-docker-smoke') "stderr=$($r.Stderr)"

    # 7. serve the way docs/cli/docker.md runs it: --allow-remote inside the container, the token
    #    from CIMPAL_API_TOKEN, and the same port inside and outside on the host loopback.
    $r = Invoke-Docker @('run', '--rm', $Image, 'serve', '--host', '0.0.0.0')
    Check 'serve refuses a non-loopback --host without --allow-remote' (($r.ExitCode -eq 2) -and ($r.Stderr -match 'allow-remote')) `
        "exit=$($r.ExitCode) stderr=$($r.Stderr)"
    $port = Get-FreePort
    do { $otherPort = Get-FreePort } while ($otherPort -eq $port)
    $token = [Convert]::ToHexString([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    $env:CIMPAL_API_TOKEN = $token
    $auth = @{ Authorization = "Bearer $token" }
    # -e without a value copies the token from this process, so it is not on the command line.
    $container = (& docker run -d --rm @readOnlyFs -p "127.0.0.1:${port}:${port}" -p "127.0.0.1:${otherPort}:${port}" `
        -e CIMPAL_API_TOKEN $Image serve --host 0.0.0.0 --allow-remote --port $port | Out-String).Trim()
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
    $code = Get-StatusCode "http://127.0.0.1:$otherPort/health"
    Check 'serve refuses a remapped host port (Host check, 403)' ($code -eq 403) "status=$code"
    $code = Get-StatusCode "http://127.0.0.1:$port/commands"
    Check 'serve refuses a request without the token (401)' ($code -eq 401) "status=$code"
    $code = Get-StatusCode "http://127.0.0.1:$port/commands" $auth
    Check 'serve accepts the token from CIMPAL_API_TOKEN' ($code -eq 200) "status=$code"
    $logs = (& docker logs $container 2>&1 | Out-String)
    Check 'serve logs where the token came from, never the token' `
        (($logs -match 'Bearer token taken from CIMPAL_API_TOKEN') -and -not $logs.Contains($token))
    $code = Get-StatusCode "http://127.0.0.1:$port/shutdown" $auth 'POST' '{}'
    $stopped = $false
    $deadline = (Get-Date).AddSeconds(20)
    while (-not $stopped -and (Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 500
        $stopped = -not (& docker ps -q --filter "id=$container")
    }
    Check 'POST /shutdown with the token stops the container' (($code -eq 200) -and $stopped) "status=$code stopped=$stopped"

    # 8. JVM defaults. -XX:+PrintCommandLineFlags is VM output, which the image sends to stderr,
    #    so stdout must still hold nothing but the version line.
    $r = Invoke-Docker (@('run', '--rm') + $readOnlyFs + @('-e', 'JAVA_OPTS=-XX:+PrintCommandLineFlags', $Image, '--version'))
    Check 'JVM defaults: MaxRAMPercentage=75 and ExitOnOutOfMemoryError' `
        (($r.Stderr -match '-XX:MaxRAMPercentage=75(\.0+)?\b') -and ($r.Stderr -match '-XX:\+ExitOnOutOfMemoryError')) "stderr=$($r.Stderr)"
    Check 'JVM output goes to stderr, stdout keeps only the CLI output' `
        (($r.ExitCode -eq 0) -and (($r.Stdout -join "`n") -eq "CimPal CLI $ExpectedVersion")) "stdout=$($r.Stdout -join ' | ')"
    $r = Invoke-Docker (@('run', '--rm') + $readOnlyFs + @('-e', 'JAVA_OPTS=-XX:MaxRAMPercentage=50 -XX:+PrintCommandLineFlags',
        $Image, '--version'))
    Check 'JAVA_OPTS overrides the JVM defaults' ($r.Stderr -match '-XX:MaxRAMPercentage=50(\.0+)?\b') "stderr=$($r.Stderr)"
    # A unified-logging warning (a missing CDS archive); by default the JVM would print it on stdout.
    $r = Invoke-Docker (@('run', '--rm') + $readOnlyFs + @('-e', 'JAVA_OPTS=-XX:SharedArchiveFile=/nonexistent.jsa',
        $Image, '--version'))
    Check 'unified-logging warnings go to stderr, stdout keeps only the CLI output' `
        (($r.Stderr -match '\[warning\]') -and (($r.Stdout -join "`n") -eq "CimPal CLI $ExpectedVersion")) `
        "stdout=$($r.Stdout -join ' | ') stderr=$($r.Stderr)"
    # A heap far too small for a validation: the JVM must end the run with exit 3 (the CLI's
    # "internal error") and say so on stderr, instead of hanging on in a broken state.
    $r = Invoke-Docker (@('run', '--rm', '--mount', "type=bind,source=$work,target=/data,readonly") + $readOnlyFs + @(
        '-e', 'JAVA_OPTS=-Xmx4m', $Image,
        'validate', '--workflow', 'mapping', '--mapping-csv', '/data/mapping.csv', '--models', '/data/models',
        '--constraints-root', '/data/constraints', '--output', '/tmp/out', '--xml-base', 'http://example.com/data',
        '--workers', '1', '--format', 'json', '--samples', '0'))
    Check 'out of memory ends the run with exit 3 and a message on stderr' `
        (($r.ExitCode -eq 3) -and ($r.Stderr -match 'Terminating due to java\.lang\.OutOfMemoryError')) `
        "exit=$($r.ExitCode) stderr=$($r.Stderr)"
    Check 'out of memory leaves stdout empty' ($r.Stdout.Count -eq 0) "stdout=$($r.Stdout -join ' | ')"

    # 9. OCI labels from the CIMPAL_VERSION and GIT_SHA build arguments
    $r = Invoke-Docker @('image', 'inspect', '-f',
        '{{index .Config.Labels "org.opencontainers.image.version"}} {{index .Config.Labels "org.opencontainers.image.revision"}}', $Image)
    $labels = ($r.Stdout -join '').Split(' ')
    Check 'the version label matches the release version' ($labels[0] -eq $ExpectedVersion) "labels=$($r.Stdout -join ' | ')"
    Check 'the revision label holds a Git commit' (($labels.Count -ge 2) -and ($labels[1] -match '^[0-9a-f]{7,40}$')) `
        "labels=$($r.Stdout -join ' | ')"
} finally {
    # Never leave a serve container behind, even when a check above threw.
    if ($container) {
        & docker rm -f $container 2>&1 | Out-Null
    }
    # Give the caller back whatever token their session had, e.g. for a serve they run themselves.
    $env:CIMPAL_API_TOKEN = $callerToken
    Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
}

if ($failures.Count -gt 0) {
    Write-Host ""
    Write-Host "$($failures.Count) check(s) failed: $($failures -join '; ')"
    exit 1
}
Write-Host ""
Write-Host "All checks passed for $Image (CimPal CLI $ExpectedVersion)."
