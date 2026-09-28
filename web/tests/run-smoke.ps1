# Runs web/tests/smoke.html in headless Chrome against an already-running local server
# (java web/server/GameServer.java) and prints the results. Real time, no virtual-time
# tricks: the test page publishes its results in document.title, which is read back
# over Chrome's remote-debugging HTTP endpoint (no WebSocket client needed).
#
#   powershell -File web/tests/run-smoke.ps1 [-Url http://localhost:8080] [-Port 9333]
param(
    [string]$Url = "http://localhost:8080",
    [int]$Port = 9333,
    [string]$Chrome = "C:\Program Files\Google\Chrome\Application\chrome.exe"
)

$profileDir = Join-Path $env:TEMP ("smoke-profile-" + [guid]::NewGuid().ToString("N"))
$proc = Start-Process -FilePath $Chrome -PassThru -ArgumentList @(
    "--headless=new", "--disable-gpu", "--enable-unsafe-swiftshader", "--use-angle=swiftshader",
    "--window-size=1100,1300", "--remote-debugging-port=$Port", "--user-data-dir=$profileDir",
    "$Url/tests/smoke.html")

$result = $null
try {
    for ($i = 0; $i -lt 90 -and -not $result; $i++) {
        Start-Sleep -Seconds 1
        try {
            $targets = Invoke-RestMethod "http://127.0.0.1:$Port/json" -TimeoutSec 2
            foreach ($t in $targets) {
                if ($t.type -eq "page" -and $t.title -like "DONE::*") { $result = $t.title }
            }
        } catch { }
    }
} finally {
    Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
    Get-CimInstance Win32_Process -Filter "Name='chrome.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -like "*$profileDir*" } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    Remove-Item -Recurse -Force $profileDir -ErrorAction SilentlyContinue
}

if (-not $result) { Write-Output "SMOKE TIMED OUT (no result published)"; exit 2 }
($result -replace '^DONE::', '') -split ' \| ' | ForEach-Object { Write-Output $_ }
if ($result -match 'SMOKE PASSED') { exit 0 } else { exit 1 }
