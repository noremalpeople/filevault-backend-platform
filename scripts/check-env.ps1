[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'

$javaHome = if ($env:FILEVAULT_JAVA_HOME) { $env:FILEVAULT_JAVA_HOME } else { $env:JAVA_HOME }
foreach ($tool in @('java', 'javac')) {
    $exe = if ($javaHome) { Join-Path $javaHome "bin/$tool.exe" } else { (Get-Command "$tool.exe").Source }
    $version = (& $exe -version 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or $version -notmatch '\b21(?:\.|\b)') {
        throw "$tool must resolve to Java 21."
    }
    Write-Output $version
}

foreach ($tool in @('mysql.exe', 'mysqld.exe', 'node.exe', 'npm.cmd', 'git.exe')) {
    $command = Get-Command $tool -ErrorAction SilentlyContinue
    if ($command) {
        & $command.Source --version
    } else {
        Write-Output "$tool is not on PATH. Use its existing installation path if available."
    }
}
foreach ($port in @(3308, 8082, 5173)) {
    $client = [Net.Sockets.TcpClient]::new()
    try {
        $client.Connect('127.0.0.1', $port)
        "127.0.0.1:$port is listening."
    } catch [Net.Sockets.SocketException] {
        "127.0.0.1:$port is not listening."
    } finally {
        $client.Dispose()
    }
}
