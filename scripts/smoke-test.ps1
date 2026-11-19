[CmdletBinding()]
param([string]$BaseUrl = 'http://127.0.0.1:8082')
$ErrorActionPreference = 'Stop'
$app = Invoke-RestMethod "$BaseUrl/api/v1/health" -TimeoutSec 10
$db = Invoke-RestMethod "$BaseUrl/actuator/health" -TimeoutSec 10
if ($app.service -ne 'filevault' -or $app.status -ne 'UP' -or $db.status -ne 'UP') {
    throw 'FileVault or its database did not report UP.'
}
Write-Output 'FileVault and its real datasource report UP.'
