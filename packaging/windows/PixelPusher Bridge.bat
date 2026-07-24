@echo off
rem Lanceur PixelPusher Bridge (Windows)
cd /d "%~dp0"

where javaw >nul 2>nul
if not errorlevel 1 (
    start "" javaw -jar PixelPusherBridge.jar
    exit /b 0
)

where java >nul 2>nul
if not errorlevel 1 (
    echo Lancement de PixelPusher Bridge... ^(ferme cette fenetre pour arreter^)
    java -jar PixelPusherBridge.jar
    exit /b 0
)

echo ERREUR : Java est introuvable sur ce PC.
echo Installe Java depuis https://adoptium.net puis relance ce fichier.
pause
