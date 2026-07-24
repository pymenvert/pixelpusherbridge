@echo off
rem Arrete toutes les instances de PixelPusher Bridge (utile si l'interface web ne repond plus).
rem Ne touche qu'aux processus Java qui executent PixelPusherBridge.jar.
echo Arret de PixelPusher Bridge...
powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='javaw.exe' or Name='java.exe'\" | Where-Object { $_.CommandLine -match 'PixelPusherBridge' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force; Write-Host ('Processus ' + $_.ProcessId + ' arrete.') }"
echo Termine.
timeout /t 3 >nul
