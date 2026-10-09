$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
if (!(Test-Path -LiteralPath 'target/classes/Operations.class')) { & "$PSScriptRoot/build.ps1" }
if (!$env:OPERATIONS_PORT) { $env:OPERATIONS_PORT = '8015' }
java -cp "target/classes;target/lib/*" Operations
