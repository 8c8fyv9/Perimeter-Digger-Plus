param(
    [string]$ModsDir = "",
    [string]$ModVersion = "1.6.10+mc26.2"
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot
$workspaceRoot = Split-Path $repoRoot

if ([string]::IsNullOrWhiteSpace($ModsDir)) {
    $ModsDir = Join-Path $workspaceRoot '.minecraft/versions/26.2-Fabric 0.20.1/mods'
}
$jarName = "perimeter-digger-plus-$ModVersion.jar"
$bundleSrc = Join-Path $repoRoot "build/libs/$jarName"
$bundleDst = Join-Path $ModsDir $jarName

if (-not (Test-Path $bundleSrc)) { throw "Missing $bundleSrc. Run scripts/build-local.ps1 first." }
if (-not (Test-Path $ModsDir)) { throw "Mods directory not found: $ModsDir. Pass -ModsDir." }

# Disable older Perimeter Digger / Perimeter Digger Plus JARs and standalone Baritone JARs
# (Baritone is bundled inside via Fabric Jar-in-Jar).
foreach ($pattern in @('perimeter-digger-*.jar', 'baritone-api-fabric-*.jar')) {
    foreach ($old in Get-ChildItem $ModsDir -Filter $pattern -ErrorAction SilentlyContinue) {
        if ($old.Name -eq $jarName) { continue }
        $disabled = "$($old.FullName).disabled"
        if (Test-Path $disabled) { Remove-Item $disabled -Force }
        Move-Item $old.FullName $disabled -Force
    }
}

Copy-Item $bundleSrc $bundleDst -Force
Write-Output "Installed single all-in-one JAR:`n  $bundleDst"
