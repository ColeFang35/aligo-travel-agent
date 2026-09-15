
$proj = 'C:\Users\Administrator\Documents\ChatGPT\基于AgentScope2.0的智能旅游助手'
$out1 = Join-Path $proj 'Agent面试资料.pdf'
$out2 = Join-Path $proj 'src\main\resources\static\Agent面试资料.pdf'
Remove-Item $out1 -ErrorAction SilentlyContinue
& 'C:\Program Files\Google\Chrome\Application\chrome.exe' --headless --disable-gpu --no-sandbox --user-data-dir='C:\Users\ADMINI~1\AppData\Local\Temp\chrome_pdf_profile2' --print-to-pdf="$out1" --no-pdf-header-footer 'file:///C:/Users/Administrator/AppData/Local/Temp/agent_bagu.html' | Out-Null
Start-Sleep -Seconds 2
$len = (Get-Item $out1).Length
Copy-Item $out1 $out2 -Force
Write-Output ('pdf=' + $len)
