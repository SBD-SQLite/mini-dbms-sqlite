param([string]$JdkHome)
$ErrorActionPreference = 'Stop'
if (-not $JdkHome) {
    $candidates = @($env:JAVA_HOME)
    $compiler = Get-Command javac -ErrorAction SilentlyContinue
    if ($compiler) { $candidates += Split-Path (Split-Path $compiler.Source -Parent) -Parent }
    $candidates += 'C:\Program Files\JetBrains\IntelliJ IDEA 2026.2.3\jbr'
    $JdkHome = $candidates | Where-Object { $_ -and (Test-Path (Join-Path $_ 'bin/javac.exe')) } | Select-Object -First 1
}
if (-not $JdkHome -or -not (Test-Path (Join-Path $JdkHome 'bin/javac.exe'))) { throw 'Pass -JdkHome with a JDK 17+ installation.' }
$build = Join-Path ([System.IO.Path]::GetTempPath()) ('qp5-build-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $build | Out-Null
$repo = Split-Path $PSScriptRoot -Parent
$roots = @('query-optimizer/src/main/java', 'concurrency-control/src/main/java', 'query-processor/src/main/java', 'query-processor/src/stubs/java', 'query-processor/src/test/java')
$sources = @($roots | ForEach-Object { Get-ChildItem -LiteralPath (Join-Path $repo $_) -Recurse -Filter '*.java' } | ForEach-Object FullName)
& (Join-Path $JdkHome 'bin/javac.exe') --release 17 -Xlint:all -Werror -encoding UTF-8 -d $build @sources
if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed.' }
& (Join-Path $JdkHome 'bin/java.exe') -cp $build sqlike.qp.Qp5AcceptanceTest
if ($LASTEXITCODE -ne 0) { throw 'QP5 checks failed.' }
