$dir = Get-Location
$p = Start-Process -FilePath java -ArgumentList @("-Djava.net.preferIPv4Stack=true", "-jar", "target\aligo-travel-agent-1.0.0.jar") -WorkingDirectory $dir -WindowStyle Hidden -RedirectStandardOutput (Join-Path $dir "target\server.out.log") -RedirectStandardError (Join-Path $dir "target\server.err.log") -PassThru
Write-Output ("Started PID " + $p.Id)
Start-Sleep 10
try { $r = Invoke-WebRequest http://localhost:8080/ -UseBasicParsing -TimeoutSec 5; Write-Output ("HTTP " + $r.StatusCode) } catch { Write-Output ("HTTP down: " + $_.Exception.Message) }