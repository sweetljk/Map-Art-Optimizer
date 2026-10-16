param(
    [Parameter(Mandatory = $true)] [string] $JavaHome,
    [Parameter(Mandatory = $true)] [string] $XaeroJar
)
$ErrorActionPreference = 'Stop'
$classpathFile = Join-Path $PSScriptRoot '.local-build/classpath.txt'
if (!(Test-Path -LiteralPath $classpathFile)) { throw 'Run build-local.ps1 first.' }
$classpath = [Collections.Generic.List[string]]::new()
foreach ($entry in Get-Content -LiteralPath $classpathFile) { $classpath.Add($entry) }
$classpath.Add((Join-Path $PSScriptRoot 'build/libs/map-art-optimizer-0.1.0.jar'))
$testDirectory = Join-Path $PSScriptRoot ('.local-build/verification-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testDirectory -Force | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead([IO.Path]::GetFullPath($XaeroJar))
try {
    foreach ($entry in $archive.Entries) {
        if ($entry.FullName -notmatch '^META-INF/jars/[^/]+\.jar$') { continue }
        $destination = Join-Path $testDirectory $entry.Name
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $destination, $true)
        $classpath.Add($destination)
    }
} finally { $archive.Dispose() }
$classes = Join-Path $testDirectory 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$classpathText = $classpath -join ';'
& (Join-Path $JavaHome 'bin/javac.exe') -proc:none -encoding UTF-8 -cp $classpathText -d $classes `
    (Join-Path $PSScriptRoot 'verification/BindingStoreReviewTest.java') `
    (Join-Path $PSScriptRoot 'verification/BridgeMemoryCheck.java')
if ($LASTEXITCODE -ne 0 -or !(Test-Path -LiteralPath (Join-Path $classes 'dev/mapframes/BindingStoreReviewTest.class')) `
        -or !(Test-Path -LiteralPath (Join-Path $classes 'BridgeMemoryCheck.class'))) { throw 'Verification source compilation failed.' }
$runtimeClasspath = "$classes;$classpathText"
& (Join-Path $JavaHome 'bin/java.exe') -cp $runtimeClasspath dev.mapframes.BindingStoreReviewTest (Join-Path $testDirectory 'bindings')
if ($LASTEXITCODE -ne 0) { throw 'Binding verification failed.' }
& (Join-Path $JavaHome 'bin/java.exe') -cp $runtimeClasspath BridgeMemoryCheck
if ($LASTEXITCODE -ne 0) { throw 'Xaero cache verification failed.' }
