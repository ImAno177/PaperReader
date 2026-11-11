$ErrorActionPreference = 'Stop'
$workspaceRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

foreach ($repository in @('sources', 'source-sample', 'theme-sample')) {
    $repositoryPath = Join-Path $workspaceRoot "external/$repository"
    if (-not (Test-Path -LiteralPath (Join-Path $repositoryPath 'gradlew.bat') -PathType Leaf)) {
        throw "Missing external/$repository. Run git submodule update --init --recursive."
    }
    Push-Location -LiteralPath $repositoryPath
    try {
        & .\gradlew.bat "-PpaperReaderSdkPath=$workspaceRoot" testDebugUnitTest lintDebug assembleDebug --console=plain
        if ($LASTEXITCODE -ne 0) {
            throw "Extension verification failed in external/$repository (exit $LASTEXITCODE)."
        }
    } finally {
        Pop-Location
    }
}
