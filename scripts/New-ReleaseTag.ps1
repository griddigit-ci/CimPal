<#
.SYNOPSIS
    Bumps all pom.xml versions, plus the hardcoded "Version:" strings shown in
    the GUI (main window footer and About dialog, including its release date),
    to a date-based release version (YYYY.MM.DD.N, auto-incrementing N if a
    release for today already exists), commits the bump, then creates and
    pushes the matching tag.

.EXAMPLE
    ./scripts/New-ReleaseTag.ps1
    ./scripts/New-ReleaseTag.ps1 -Remote upstream -DryRun
    ./scripts/New-ReleaseTag.ps1 -NoPush
#>
param(
    [string]$Remote = "origin",
    [switch]$DryRun,
    [switch]$NoPush
)

$ErrorActionPreference = "Stop"

$repoRoot = git rev-parse --show-toplevel
Set-Location $repoRoot

if (git status --porcelain) {
    throw "Working tree is not clean. Commit or stash changes before releasing."
}

$rootPom = Join-Path $repoRoot "pom.xml"
$pomFiles = @(
    $rootPom,
    (Join-Path $repoRoot "CimPal-Core\pom.xml"),
    (Join-Path $repoRoot "CimPal-Main\pom.xml"),
    (Join-Path $repoRoot "CimPal-CustomWriter\pom.xml"),
    (Join-Path $repoRoot "CimPal-CLI\pom.xml")
)

# GUI files with a hardcoded "Version: ..." string that isn't wired to the pom
# version at build time (main window footer, About dialog) - kept in sync here.
$mainGuiFxml = Join-Path $repoRoot "CimPal-Main\src\main\resources\fxml\CimPalGui.fxml"
$aboutGuiFxml = Join-Path $repoRoot "CimPal-Main\src\main\resources\fxml\aboutGui.fxml"
$versionFiles = $pomFiles + @($mainGuiFxml, $aboutGuiFxml)

$rootContent = Get-Content $rootPom -Raw
if ($rootContent -notmatch '<cimpal\.version>([^<]+)</cimpal\.version>') {
    throw "Could not find <cimpal.version> in $rootPom"
}
$oldVersion = $Matches[1]

$datePrefix = Get-Date -Format "yyyy.M.d"
$legacyDatePrefix = Get-Date -Format "yyyy.MM.dd"
$existing = @(git tag -l "$datePrefix.*" "$legacyDatePrefix.*")
$nextN = 1
if ($existing) {
    $numbers = $existing | ForEach-Object { [int]$_.Split('.')[-1] }
    $nextN = ($numbers | Measure-Object -Maximum).Maximum + 1
}
$newVersion = "$datePrefix.$nextN"

Write-Host "Current version: $oldVersion"
Write-Host "New version:     $newVersion"

if ($DryRun) {
    Write-Host "Dry run - no files changed, nothing committed, nothing tagged/pushed."
    exit 0
}

$escapedOld = [regex]::Escape($oldVersion)
foreach ($file in $versionFiles) {
    $content = Get-Content $file -Raw
    $updated = $content -replace $escapedOld, $newVersion
    if ($updated -eq $content) {
        throw "No occurrence of version $oldVersion found in $file - aborting before partial update."
    }
    Set-Content -Path $file -Value $updated -NoNewline
}

$newDate = (Get-Date).ToString("dd-MMM-yyyy", [System.Globalization.CultureInfo]::InvariantCulture)
$aboutContent = Get-Content $aboutGuiFxml -Raw
$updatedAbout = $aboutContent -replace 'Date: \d{2}-[A-Za-z]{3}-\d{4}', "Date: $newDate"
if ($updatedAbout -eq $aboutContent) {
    throw "Could not find release date pattern in $aboutGuiFxml - aborting before partial update."
}
Set-Content -Path $aboutGuiFxml -Value $updatedAbout -NoNewline

Write-Host "Validating reactor after version bump..."
mvn -q -N validate
if ($LASTEXITCODE -ne 0) {
    throw "mvn validate failed after version bump - review the pom changes before committing."
}

git add $versionFiles
git commit -m "Bump version to $newVersion"
git tag -a $newVersion -m "Release $newVersion"

if ($NoPush) {
    Write-Host "Committed and tagged $newVersion locally (not pushed - rerun without -NoPush, or push manually)."
    exit 0
}

git push $Remote HEAD
git push $Remote $newVersion

Write-Host "Bumped, committed, tagged, and pushed release $newVersion"