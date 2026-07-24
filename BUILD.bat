@echo off
setlocal
cd /d "%~dp0"

echo ============================================
echo  PixelPusher Bridge - Compilation
echo ============================================

echo Arret des instances en cours (elles verrouillent le jar)...
powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='javaw.exe' or Name='java.exe'\" | Where-Object { $_.CommandLine -match 'PixelPusherBridge' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }" >nul 2>nul
timeout /t 2 /nobreak >nul


where javac >nul 2>nul
if errorlevel 1 (
    echo ERREUR : javac introuvable. Installe un JDK ^(adoptium.net^) et reessaie.
    pause
    exit /b 1
)

if exist build rd /s /q build
mkdir build\classes
if exist sources_list.txt del sources_list.txt

for /R src %%f in (*.java) do (
    echo %%f | find /I "Test.java" >nul
    if errorlevel 1 echo %%f >> sources_list.txt
)

echo Compilation ^(cible Java 11^)...
javac --release 11 -encoding UTF-8 -d build\classes @sources_list.txt 2>build\javac_err.txt
if errorlevel 1 (
    echo Option --release non supportee ou erreur, nouvel essai en mode standard...
    javac -encoding UTF-8 -d build\classes @sources_list.txt
    if errorlevel 1 (
        echo.
        echo ERREUR DE COMPILATION - details ci-dessus et dans build\javac_err.txt
        pause
        exit /b 1
    )
)
if exist build\javac_err.txt type build\javac_err.txt

echo Integration de l'interface web...
mkdir build\classes\web
copy /Y web\*.html build\classes\web\ >nul

if not exist dist mkdir dist
echo Creation du JAR...
jar cfe dist\PixelPusherBridge.jar com.pixelpusher.bridge.Main -C build\classes .
if errorlevel 1 (
    echo ERREUR lors de la creation du JAR.
    pause
    exit /b 1
)

del sources_list.txt

echo Mise a jour du dossier Windows...
if not exist "dist\PixelPusher Bridge (Windows)" mkdir "dist\PixelPusher Bridge (Windows)"
copy /Y dist\PixelPusherBridge.jar "dist\PixelPusher Bridge (Windows)\" >nul
if errorlevel 1 (
    echo ERREUR : copie du jar impossible ^(une instance tourne encore ?^)
    pause
    exit /b 1
)
copy /Y "packaging\windows\PixelPusher Bridge.bat" "dist\PixelPusher Bridge (Windows)\" >nul
copy /Y "packaging\windows\Arreter PixelPusher Bridge.bat" "dist\PixelPusher Bridge (Windows)\" >nul

rem Met aussi a jour le jar dans l'app macOS si elle a deja ete assemblee
if exist "dist\PixelPusher Bridge.app\Contents\Resources" (
    copy /Y dist\PixelPusherBridge.jar "dist\PixelPusher Bridge.app\Contents\Resources\" >nul
    echo App macOS mise a jour.
)

echo.
echo ============================================
echo  OK ! Fichiers generes dans le dossier dist\
echo   - PixelPusherBridge.jar
echo   - PixelPusher Bridge (W