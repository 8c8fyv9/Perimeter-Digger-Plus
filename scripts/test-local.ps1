param(
    [string]$JdkBin = "",
    [string]$MinecraftDir = "",
    [string]$VersionName = "26.2-Fabric 0.20.1",
    [string]$JunitConsoleJar = ""
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot
$workspaceRoot = Split-Path $repoRoot

if ([string]::IsNullOrWhiteSpace($JdkBin)) {
    $candidateJdk = Join-Path $workspaceRoot 'autobuild/build/jdk25/jdk-25.0.4.1+1/bin'
    if (Test-Path (Join-Path $candidateJdk 'javac.exe')) {
        $JdkBin = $candidateJdk
    } else {
        $javacCmd = Get-Command javac -ErrorAction Stop
        $JdkBin = Split-Path $javacCmd.Source
    }
}
if ([string]::IsNullOrWhiteSpace($MinecraftDir)) {
    $MinecraftDir = Join-Path $workspaceRoot '.minecraft'
}
if ([string]::IsNullOrWhiteSpace($JunitConsoleJar)) {
    $localJunit = Join-Path $repoRoot 'deps/junit-console.jar'
    $siblingJunit = Join-Path $workspaceRoot 'perimeter-digger/deps/junit-console.jar'
    if (Test-Path $localJunit) { $JunitConsoleJar = $localJunit }
    elseif (Test-Path $siblingJunit) { $JunitConsoleJar = $siblingJunit }
    else { throw "Missing junit-platform-console-standalone jar. Pass -JunitConsoleJar." }
}

$mcJar = Join-Path $MinecraftDir "versions/$VersionName/$VersionName.jar"
$baritoneRoot = Join-Path $repoRoot 'baritone'

# 1. Node source-contract tests
node --test "$repoRoot/agent/client.test.mjs"
if ($LASTEXITCODE -ne 0) { throw 'Node contract tests failed' }

# 2. Baritone fork JUnit tests
New-Item -ItemType Directory -Force "$baritoneRoot/build/test-classes" | Out-Null
$bTests = @(
    "$baritoneRoot/src/test/java/baritone/process/PlacementBackoffTest.java",
    "$baritoneRoot/src/test/java/baritone/process/ScaffoldTrackerTest.java",
    "$baritoneRoot/src/test/java/baritone/api/process/area/SealedPocketDetectorTest.java",
    "$baritoneRoot/src/test/java/baritone/api/process/area/AreaMiningStrategyPlannerTest.java",
    "$baritoneRoot/src/test/java/baritone/api/utils/HotbarSwapPlannerTest.java",
    "$baritoneRoot/src/test/java/baritone/api/utils/StuckCellPenaltyTest.java"
)
& (Join-Path $JdkBin 'javac.exe') --release 25 -proc:none -encoding UTF-8 -cp "$baritoneRoot/build/classes;$JunitConsoleJar" -d "$baritoneRoot/build/test-classes" @bTests
if ($LASTEXITCODE -ne 0) { throw 'Baritone test compilation failed' }
& (Join-Path $JdkBin 'java.exe') -cp "$baritoneRoot/build/test-classes;$baritoneRoot/build/classes;$JunitConsoleJar" org.junit.platform.console.ConsoleLauncher execute --disable-ansi-colors `
    --select-class baritone.process.PlacementBackoffTest `
    --select-class baritone.process.ScaffoldTrackerTest `
    --select-class baritone.api.process.area.SealedPocketDetectorTest `
    --select-class baritone.api.process.area.AreaMiningStrategyPlannerTest `
    --select-class baritone.api.utils.HotbarSwapPlannerTest `
    --select-class baritone.api.utils.StuckCellPenaltyTest
if ($LASTEXITCODE -ne 0) { throw 'Baritone JUnit tests failed' }

# 3. Perimeter Digger Plus JUnit tests
New-Item -ItemType Directory -Force "$repoRoot/build/test-classes" | Out-Null
$testLibs = @(
    "$repoRoot/build/classes",
    "$repoRoot/src/main/resources",
    "$baritoneRoot/build/classes",
    $JunitConsoleJar,
    $mcJar
) + @(Get-ChildItem (Join-Path $MinecraftDir 'libraries') -Recurse -Filter '*.jar' -ErrorAction SilentlyContinue | ForEach-Object FullName)
$pTests = @(Get-ChildItem "$repoRoot/src/test/java" -Recurse -Filter '*.java' | ForEach-Object FullName)
$tLines = @('--release', '25', '-proc:none', '-encoding', 'UTF-8', '-cp', ('"' + ($testLibs -join ';') + '"'), '-d', ('"' + "$repoRoot/build/test-classes" + '"')) + @($pTests | ForEach-Object { '"' + $_ + '"' })
$tLines = @($tLines | ForEach-Object { $_.Replace('\', '/') })
[IO.File]::WriteAllLines("$repoRoot/build/test-javac.args", $tLines)
& (Join-Path $JdkBin 'javac.exe') "@$repoRoot/build/test-javac.args"
if ($LASTEXITCODE -ne 0) { throw 'Perimeter test compilation failed' }
& (Join-Path $JdkBin 'java.exe') -cp "$repoRoot/build/test-classes;$($testLibs -join ';')" org.junit.platform.console.ConsoleLauncher execute --disable-ansi-colors --scan-classpath "$repoRoot/build/test-classes"
if ($LASTEXITCODE -ne 0) { throw 'Perimeter JUnit tests failed' }
