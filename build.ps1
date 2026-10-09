$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$lib = Join-Path $projectRoot 'target/lib'
$classes = Join-Path $projectRoot 'target/classes'
New-Item -ItemType Directory -Force $lib, $classes | Out-Null
$dependencies = @(
    @{ Name = 'h2-2.3.232.jar'; Url = 'https://repo.maven.apache.org/maven2/com/h2database/h2/2.3.232/h2-2.3.232.jar' },
    @{ Name = 'gson-2.11.0.jar'; Url = 'https://repo.maven.apache.org/maven2/com/google/code/gson/gson/2.11.0/gson-2.11.0.jar' }
)
foreach ($dependency in $dependencies) {
    $destination = Join-Path $lib $dependency.Name
    if (!(Test-Path -LiteralPath $destination)) {
        Invoke-WebRequest -UseBasicParsing -Uri $dependency.Url -OutFile $destination
    }
    $expected = (Invoke-WebRequest -UseBasicParsing -Uri ($dependency.Url + '.sha1')).Content.Trim().Split(' ')[0]
    if ((Get-FileHash -LiteralPath $destination -Algorithm SHA1).Hash.ToLowerInvariant() -ne $expected.ToLowerInvariant()) {
        throw "Dependency checksum mismatch: $($dependency.Name)"
    }
}
javac -cp "$lib/*" -d $classes "$projectRoot/src/Operations.java" "$projectRoot/src/OperationsTest.java"
if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed' }
Write-Output 'Compiled. Run java -cp "target/classes;target/lib/*" Operations'
