param(
    [Parameter(Mandatory=$true)][string]$ConnectorJar,
    [Parameter(Mandatory=$true)][string]$QuizConfig,
    [string]$TestConfig = 'config/database.test.properties'
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
function Checked([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program exited with code $LASTEXITCODE" }
}
Push-Location $repo
try {
    foreach ($file in @($ConnectorJar, $QuizConfig, $TestConfig)) {
        if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "Missing test dependency/config file: $file" }
    }
    $connector = (Resolve-Path -LiteralPath $ConnectorJar).Path
    $quiz = (Resolve-Path -LiteralPath $QuizConfig).Path
    $database = (Resolve-Path -LiteralPath $TestConfig).Path
    New-Item -ItemType Directory -Path out -Force | Out-Null
    $sources = @(Get-ChildItem backend -Recurse -File -Filter '*.java' | ForEach-Object FullName)
    Write-Output 'Compiling backend with javac --release 17 -cp lib/* -d out <all backend Java files>'
    Checked 'javac' (@('--release','17','-cp','lib/*','-d','out') + $sources)
    $classpath = "out;lib/*;$connector"
    foreach ($name in @('DifficultyPlanStrategyTest','ReschedulerTest','GeminiConfigTest')) {
        Checked 'java' @('-cp',$classpath,"backend.test.$name")
    }
    foreach ($name in @('AuthServiceIntegrationTest','AdminIntegrationTest','ReminderTest','DeleteExamTest')) {
        Checked 'java' @('-cp',$classpath,"backend.test.$name",$database)
    }
    Checked 'java' @('-cp',$classpath,'backend.test.QuizTest',$quiz)
    Checked 'node' @('--test','tests/frontend-session.test.mjs')
    Write-Output 'PASS all 8 Java test programs and 5 frontend session tests'
} finally { Pop-Location }
