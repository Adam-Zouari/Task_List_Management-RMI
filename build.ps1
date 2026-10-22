[CmdletBinding()]
param([switch]$Test)

$ErrorActionPreference = 'Stop'
Push-Location -LiteralPath $PSScriptRoot
try {
    New-Item -ItemType Directory -Path out -Force | Out-Null
    $sources = @(Get-ChildItem -LiteralPath src -Filter '*.java' -File | Select-Object -ExpandProperty FullName)
    if ($Test) {
        $sources += @(Get-ChildItem -LiteralPath tests -Filter '*.java' -File | Select-Object -ExpandProperty FullName)
    }
    & javac --release 11 -Xlint:all -Werror -d out @sources
    if ($LASTEXITCODE -ne 0) { throw 'Compilation failed.' }
    Write-Output 'Build complete: compiled classes are in out/.'
    if ($Test) {
        foreach ($testClass in @('TaskManagerImplTest', 'TaskStoreTest', 'TaskIntegrationTest', 'TaskClientTest', 'TaskTableModelTest', 'TaskTransportTest')) {
            & java '-Djava.awt.headless=true' -cp out $testClass
            if ($LASTEXITCODE -ne 0) { throw "$testClass failed." }
        }
    }
} finally {
    Pop-Location
}
