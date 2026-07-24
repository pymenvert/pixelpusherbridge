@echo off
setlocal
cd /d "%~dp0"

echo ============================================
echo  PixelPusher Bridge - banc de tests
echo ============================================

where javac >nul 2>nul
if errorlevel 1 (
    echo ERREUR : javac introuvable. Installe un JDK ^(adoptium.net^) et reessaie.
    exit /b 1
)

if exist build\tests rd /s /q build\tests
mkdir build\tests
if exist test_sources.txt del test_sources.txt

for /R src %%f in (*.java) do echo %%f >> test_sources.txt
for /R tests %%f in (*.java) do echo %%f >> test_sources.txt

echo Compilation des sources et des tests...
javac --release 11 -encoding UTF-8 -d build\tests @test_sources.txt
if errorlevel 1 (
    del test_sources.txt
    echo.
    echo ERREUR DE COMPILATION
    exit /b 1
)
del test_sources.txt

echo.
java -cp build\tests com.pixelpusher.bridge.RunTests
set RESULTAT=%errorlevel%

if %RESULTAT% NEQ 0 (
    echo.
    echo Des tests ont echoue. Ne publie pas cette version.
    exit /b %RESULTAT%
)

where python >nul 2>nul
if not errorlevel 1 (
    echo.
    echo Verification des interfaces web...
    python tools\check_web.py
    if errorlevel 1 (
        echo.
        echo Les interfaces web contiennent une erreur. Ne publie pas cette version.
        exit /b 1
    )

    rem Validation du QR code par un decodeur independant (voir DEVNOTES : OpenCV
    rem n'est pas un juge fiable, ce script embarque son propre decodeur).
    if exist dist\PixelPusherBridge.jar (
        echo.
        echo Validation de l'encodeur QR...
        python tools\validate_qr.py dist\PixelPusherBridge.jar
        if errorlevel 1 exit /b 1
    )
)

echo.
echo Tout est au vert.
exit /b 0
