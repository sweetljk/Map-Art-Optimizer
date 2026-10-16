param(
    [string] $DependencyCache = $(if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }),
    [string] $GradleExecutable = '',
    [string] $JavaHome = $env:JAVA_HOME,
    [string] $XaeroJar = '',
    [string[]] $Tasks = @('build')
)

$ErrorActionPreference = 'Stop'
$DependencyCache = [IO.Path]::GetFullPath($DependencyCache)
$localBuild = Join-Path $PSScriptRoot '.local-build'
$moduleCache = Join-Path $DependencyCache 'caches/modules-2/files-2.1'
New-Item -ItemType Directory -Path $localBuild -Force | Out-Null

function Find-CachedJar([string] $Group, [string] $Artifact, [string] $Version) {
    $versionDirectory = Join-Path (Join-Path (Join-Path $moduleCache $Group) $Artifact) $Version
    if (!(Test-Path -LiteralPath $versionDirectory)) { return $null }
    $expectedName = "$Artifact-$Version.jar"
    return Get-ChildItem -LiteralPath $versionDirectory -Recurse -File |
        Where-Object { $_.Name -ceq $expectedName } |
        Select-Object -First 1 -ExpandProperty FullName
}

if (!$JavaHome -or !(Test-Path -LiteralPath (Join-Path $JavaHome 'bin/javac.exe'))) {
    throw 'Set JAVA_HOME to an installed JDK 25 before running this script.'
}
if (!$GradleExecutable) {
    $GradleExecutable = Get-ChildItem -LiteralPath (Join-Path $DependencyCache 'wrapper/dists') -Recurse -File -Filter gradle.bat |
        Where-Object { $_.FullName -match '[\\/]gradle-9\.6\.0[\\/]bin[\\/]gradle\.bat$' } |
        Select-Object -First 1 -ExpandProperty FullName
}
if (!$GradleExecutable -or !(Test-Path -LiteralPath $GradleExecutable)) {
    throw 'Pass -GradleExecutable with the path to an installed Gradle 9.6.0 gradle.bat.'
}
if (!$XaeroJar) { $XaeroJar = Join-Path $PSScriptRoot 'libs/xaeroworldmap-fabric-26.1.2-1.41.3.jar' }
if (!(Test-Path -LiteralPath $XaeroJar)) { throw "Xaero World Map 1.41.3 is required for compilation: $XaeroJar" }
$XaeroJar = [IO.Path]::GetFullPath($XaeroJar)

$minecraftCache = Join-Path $DependencyCache 'caches/fabric-loom/26.1.2'
$minecraftJar = Join-Path $minecraftCache 'minecraft-client.jar'
$manifestFile = Join-Path $minecraftCache 'mojang_minecraft_info.json'
if (!(Test-Path -LiteralPath $minecraftJar) -or !(Test-Path -LiteralPath $manifestFile)) {
    throw 'Minecraft 26.1.2 must already be present in the specified Fabric Loom dependency cache.'
}
$classpath = [Collections.Generic.List[string]]::new()
$classpath.Add($minecraftJar)
$manifest = Get-Content -LiteralPath $manifestFile -Raw | ConvertFrom-Json
foreach ($library in $manifest.libraries) {
    $parts = $library.name.Split(':')
    if ($parts.Length -ne 3) { continue }
    $libraryJar = Find-CachedJar $parts[0] $parts[1] $parts[2]
    if ($libraryJar) { $classpath.Add($libraryJar) }
}
foreach ($coordinate in @(
    @('net.fabricmc', 'fabric-loader', '0.19.3'),
    @('net.fabricmc', 'sponge-mixin', '0.17.3+mixin.0.8.7')
)) {
    $dependencyJar = Find-CachedJar $coordinate[0] $coordinate[1] $coordinate[2]
    if (!$dependencyJar) { throw "Required dependency is absent from the cache: $($coordinate -join ':')" }
    $classpath.Add($dependencyJar)
}
$fabricApi = Find-CachedJar 'net.fabricmc.fabric-api' 'fabric-api' '0.155.3+26.1.2'
if (!$fabricApi) { throw 'Fabric API 0.155.3+26.1.2 is absent from the cache.' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$apiDirectory = Join-Path $localBuild 'fabric-api'
New-Item -ItemType Directory -Path $apiDirectory -Force | Out-Null
$apiArchive = [IO.Compression.ZipFile]::OpenRead($fabricApi)
try {
    foreach ($entry in $apiArchive.Entries) {
        if ($entry.FullName -notmatch '^META-INF/jars/[^/]+\.jar$') { continue }
        $destination = Join-Path $apiDirectory $entry.Name
        if (!(Test-Path -LiteralPath $destination) -or (Get-Item -LiteralPath $destination).Length -ne $entry.Length) {
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $destination, $true)
        }
        $classpath.Add($destination)
    }
} finally { $apiArchive.Dispose() }
$classpath.Add($XaeroJar)

$classpathFile = Join-Path $localBuild 'classpath.txt'
[IO.File]::WriteAllLines($classpathFile, ($classpath | Select-Object -Unique), [Text.UTF8Encoding]::new($false))

$previousJavaHome = $env:JAVA_HOME
$previousGradleHome = $env:GRADLE_USER_HOME
$previousJavaOptions = $env:JAVA_TOOL_OPTIONS
try {
    $env:JAVA_HOME = $JavaHome
    $env:GRADLE_USER_HOME = Join-Path $localBuild 'gradle-home'
    $env:JAVA_TOOL_OPTIONS = "$previousJavaOptions -Dorg.gradle.native=false".Trim()
    Push-Location $PSScriptRoot
    try {
        & $GradleExecutable --offline --no-daemon --console=plain '-PofflineLocal=true' "-PlocalClasspathFile=$classpathFile" "-PxaeroJar=$XaeroJar" @Tasks
        if ($LASTEXITCODE -ne 0) { throw "Gradle exited with code $LASTEXITCODE." }
    } finally { Pop-Location }
} finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:GRADLE_USER_HOME = $previousGradleHome
    $env:JAVA_TOOL_OPTIONS = $previousJavaOptions
}
