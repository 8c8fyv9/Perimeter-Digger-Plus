param(
    [string]$JdkBin = "",
    [string]$MinecraftDir = "",
    [string]$VersionName = "26.2-Fabric 0.20.1",
    [string]$BaritoneDepsDir = "",
    [string]$ModVersion = "1.6.10+mc26.2"
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
$instanceDir = Join-Path $MinecraftDir "versions/$VersionName"
$mcJar = Join-Path $instanceDir "$VersionName.jar"
if (-not (Test-Path $mcJar)) {
    throw "Minecraft version JAR not found: $mcJar. Pass -MinecraftDir and -VersionName."
}
if ([string]::IsNullOrWhiteSpace($BaritoneDepsDir)) {
    $localDeps = Join-Path $repoRoot 'baritone/deps'
    $siblingDeps = Join-Path $workspaceRoot 'perimeter-digger-baritone/deps'
    if (Test-Path $localDeps) { $BaritoneDepsDir = $localDeps }
    elseif (Test-Path $siblingDeps) { $BaritoneDepsDir = $siblingDeps }
    else { throw "Baritone compile deps not found (expected jsr305.jar, annotations.jar, upstream-26.1.1.jar). Pass -BaritoneDepsDir." }
}

# 1. Compile and package embedded Baritone fork
$baritoneRoot = Join-Path $repoRoot 'baritone'
New-Item -ItemType Directory -Force "$baritoneRoot/build/classes", "$baritoneRoot/build/deps" | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$netherJar = "$baritoneRoot/build/deps/nether-pathfinder.jar"
$upstreamBaritone = Join-Path $BaritoneDepsDir 'upstream-26.1.1.jar'
if (Test-Path $upstreamBaritone) {
    $zip = [IO.Compression.ZipFile]::OpenRead($upstreamBaritone)
    try {
        $entry = $zip.GetEntry('META-INF/jars/nether-pathfinder-1.6-windows-hr.1.jar')
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $netherJar, $true)
    } finally { $zip.Dispose() }
} elseif (-not (Test-Path $netherJar)) {
    throw "Missing nether-pathfinder dependency jar in $BaritoneDepsDir"
}

$asmMainJars = @(Get-ChildItem (Join-Path $MinecraftDir 'libraries/org/ow2/asm/asm') -Recurse -Filter 'asm-*.jar' -ErrorAction SilentlyContinue | Sort-Object Name -Descending | ForEach-Object FullName)
$baritoneLibs = @(
    $mcJar,
    $netherJar,
    (Join-Path $BaritoneDepsDir 'jsr305.jar'),
    (Join-Path $BaritoneDepsDir 'annotations.jar')
) + $asmMainJars + @(Get-ChildItem (Join-Path $MinecraftDir 'libraries') -Recurse -Filter '*.jar' | ForEach-Object FullName)

$baritoneSources = @('api', 'main', 'launch', 'schematica_api') | ForEach-Object {
    Get-ChildItem "$baritoneRoot/src/$_/java" -Recurse -Filter '*.java' | ForEach-Object FullName
}
$bLines = @('--release', '25', '-encoding', 'UTF-8', '-proc:none', '-Xmaxerrs', '300',
    '-cp', ('"' + ($baritoneLibs -join ';') + '"'),
    '-d', ('"' + "$baritoneRoot/build/classes" + '"')) + @($baritoneSources | ForEach-Object { '"' + $_ + '"' })
$bLines = @($bLines | ForEach-Object { $_.Replace('\', '/') })
[IO.File]::WriteAllLines("$baritoneRoot/build/javac.args", $bLines)
$ErrorActionPreference = 'Continue'
& (Join-Path $JdkBin 'javac.exe') "-J-Duser.language=en" "@$baritoneRoot/build/javac.args"
if ($LASTEXITCODE -ne 0) { throw 'Baritone compilation failed' }
$ErrorActionPreference = 'Stop'

$bStage = "$baritoneRoot/build/runtime-stage"
if (Test-Path $bStage) { Remove-Item $bStage -Recurse -Force }
New-Item -ItemType Directory -Force $bStage, "$bStage/META-INF/jars", "$baritoneRoot/build/libs" | Out-Null
Copy-Item "$baritoneRoot/build/classes/baritone" $bStage -Recurse -Force
Copy-Item "$baritoneRoot/src/launch/resources/mixins.baritone.json" $bStage -Force
Copy-Item $netherJar "$bStage/META-INF/jars/nether-pathfinder-1.6-windows-hr.1.jar" -Force
$bMeta = Get-Content "$baritoneRoot/fabric/src/main/resources/fabric.mod.json" -Raw | ConvertFrom-Json
$bMeta.version = '1.18.0+mc26.2.local.1'
$bMeta.environment = 'client'
$bMeta | Add-Member -NotePropertyName jars -NotePropertyValue @(@{ file = 'META-INF/jars/nether-pathfinder-1.6-windows-hr.1.jar' }) -Force
$bMeta.depends | Add-Member -NotePropertyName java -NotePropertyValue '>=25' -Force
$bMeta.PSObject.Properties.Remove('icon')
$bMeta | ConvertTo-Json -Depth 20 | Set-Content "$bStage/fabric.mod.json" -Encoding UTF8
Copy-Item "$baritoneRoot/LICENSE*" $bStage -Force
$baritoneJar = "$baritoneRoot/build/libs/baritone-api-fabric-26.2-local.1.jar"
if (Test-Path $baritoneJar) { Remove-Item $baritoneJar -Force }
& (Join-Path $JdkBin 'jar.exe') --create --file $baritoneJar -C $bStage .
if ($LASTEXITCODE -ne 0) { throw 'Baritone packaging failed' }

# 2. Compile and package Perimeter Digger Plus single Jar-in-Jar
New-Item -ItemType Directory -Force "$repoRoot/build/classes" | Out-Null
$modLibs = @(
    $mcJar,
    "$baritoneRoot/build/classes"
) + @(Get-ChildItem (Join-Path $instanceDir '.fabric/processedMods') -Filter '*.jar' -ErrorAction SilentlyContinue | ForEach-Object FullName) +
    @(Get-ChildItem (Join-Path $MinecraftDir 'libraries') -Recurse -Filter '*.jar' | ForEach-Object FullName)

$modSources = @(Get-ChildItem "$repoRoot/src/main/java", "$repoRoot/src/client/java" -Recurse -Filter '*.java' | ForEach-Object FullName)
$mLines = @('--release', '25', '-encoding', 'UTF-8', '-proc:none', '-Xmaxerrs', '200',
    '-cp', ('"' + ($modLibs -join ';') + '"'),
    '-d', ('"' + "$repoRoot/build/classes" + '"')) + @($modSources | ForEach-Object { '"' + $_ + '"' })
$mLines = @($mLines | ForEach-Object { $_.Replace('\', '/') })
[IO.File]::WriteAllLines("$repoRoot/build/javac.args", $mLines)
$ErrorActionPreference = 'Continue'
& (Join-Path $JdkBin 'javac.exe') "-J-Duser.language=en" "@$repoRoot/build/javac.args"
if ($LASTEXITCODE -ne 0) { throw 'Perimeter Digger Plus compilation failed' }
$ErrorActionPreference = 'Stop'

$stage = "$repoRoot/build/stage"
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory -Force $stage, "$stage/META-INF/jars", "$repoRoot/build/libs" | Out-Null
Copy-Item "$repoRoot/build/classes/*" $stage -Recurse -Force
Copy-Item "$repoRoot/src/main/resources/*" $stage -Recurse -Force
if (Test-Path "$repoRoot/src/client/resources") { Copy-Item "$repoRoot/src/client/resources/*" $stage -Recurse -Force }
Copy-Item $baritoneJar "$stage/META-INF/jars/baritone-api-fabric-26.2-local.1.jar" -Force
$meta = Get-Content "$repoRoot/src/main/resources/fabric.mod.json" -Raw | ConvertFrom-Json
$meta.version = $ModVersion
$meta.depends.baritone = '1.18.0+mc26.2.local.1'
$meta | Add-Member -NotePropertyName jars -NotePropertyValue @(@{ file = 'META-INF/jars/baritone-api-fabric-26.2-local.1.jar' }) -Force
$meta | ConvertTo-Json -Depth 20 | Set-Content "$stage/fabric.mod.json" -Encoding UTF8
Copy-Item "$repoRoot/LICENSE" $stage -Force
$out = "$repoRoot/build/libs/perimeter-digger-plus-$ModVersion.jar"
if (Test-Path $out) { Remove-Item $out -Force }
& (Join-Path $JdkBin 'jar.exe') --create --file $out -C $stage .
if ($LASTEXITCODE -ne 0) { throw 'Perimeter Digger Plus packaging failed' }
Write-Output $out
