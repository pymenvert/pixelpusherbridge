@echo off
rem ============================================================
rem  Lanceur PixelPusher Bridge (Windows)
rem
rem  Ne se contente PAS de verifier que java existe : il faut
rem  verifier qu'il FONCTIONNE et qu'il est assez recent. Un java
rem  present mais casse (installation partielle, JAVA_HOME qui
rem  pointe dans le vide, version 8 trop ancienne) faisait echouer
rem  le demarrage sans le moindre message : la fenetre se fermait
rem  et rien ne se passait. C'est le meme piege que celui deja
rem  documente pour macOS dans DEVNOTES.md, jamais reporte ici.
rem ============================================================
setlocal enabledelayedexpansion
cd /d "%~dp0"

set "LOGDIR=%USERPROFILE%\.pixelpusherbridge"
if not exist "%LOGDIR%" mkdir "%LOGDIR%" >nul 2>nul
set "LOG=%LOGDIR%\launcher.log"
echo [%date% %time%] Demarrage du lanceur>>"%LOG%"

if not exist "PixelPusherBridge.jar" (
    echo ERREUR : PixelPusherBridge.jar est introuvable a cote de ce fichier.
    echo Garde le dossier complet, ne deplace pas ce raccourci tout seul.
    echo [%date% %time%] jar introuvable>>"%LOG%"
    pause
    exit /b 1
)

rem --- Recherche d'un Java qui fonctionne vraiment ---
set "JAVABIN="
if defined JAVA_HOME call :tester "%JAVA_HOME%\bin\java.exe"
call :tester "java"
for /d %%d in ("%ProgramFiles%\Eclipse Adoptium\jdk-*") do call :tester "%%d\bin\java.exe"
for /d %%d in ("%ProgramFiles%\Eclipse Adoptium\jre-*") do call :tester "%%d\bin\java.exe"
for /d %%d in ("%ProgramFiles%\Java\jdk-*") do call :tester "%%d\bin\java.exe"
for /d %%d in ("%ProgramFiles%\Java\jre-*") do call :tester "%%d\bin\java.exe"
if defined JAVABIN goto lancer

echo.
echo ============================================================
echo  ERREUR : aucun Java utilisable n'a ete trouve sur ce PC.
echo.
echo  PixelPusher Bridge a besoin de Java 11 ou plus recent.
echo  Telecharge-le gratuitement ici :
echo.
echo      https://adoptium.net
echo.
echo  Choisis "Temurin JRE", installe, puis relance ce fichier.
echo ============================================================
echo [%date% %time%] aucun java valide>>"%LOG%"
start "" https://adoptium.net
pause
exit /b 1

:lancer
echo [%date% %time%] Java retenu : !JAVABIN!>>"%LOG%"
rem javaw lance sans fenetre de console ; on retombe sur java s'il est absent.
if /i "!JAVABIN!"=="java" (
    where javaw >nul 2>nul
    if not errorlevel 1 (
        start "" javaw -jar PixelPusherBridge.jar
        exit /b 0
    )
) else (
    set "JAVAW=!JAVABIN:java.exe=javaw.exe!"
    if exist "!JAVAW!" (
        start "" "!JAVAW!" -jar PixelPusherBridge.jar
        exit /b 0
    )
)
echo Lancement de PixelPusher Bridge... ^(ferme cette fenetre pour arreter^)
"!JAVABIN!" -jar PixelPusherBridge.jar
exit /b 0

rem --- Teste un candidat : doit repondre a -version ET etre en version >= 11 ---
:tester
if defined JAVABIN exit /b 0
set "CANDIDAT=%~1"
if "%CANDIDAT%"=="" exit /b 0
if /i not "%CANDIDAT%"=="java" if not exist "%CANDIDAT%" exit /b 0
"%CANDIDAT%" -version >nul 2>nul
if errorlevel 1 (
    echo [%date% %time%] candidat inutilisable : %CANDIDAT%>>"%LOG%"
    exit /b 0
)
rem Version majeure : "21.0.7" donne 21 ; "1.8.0_392" donne 1, donc rejete.
set "VER="
for /f tokens^=3 %%v in ('""%CANDIDAT%" -version 2>&1 | findstr /i "version""') do (
    if not defined VER set "VER=%%~v"
)
if not defined VER exit /b 0
for /f "tokens=1 delims=._" %%m in ("!VER!") do set "MAJ=%%m"
if not defined MAJ exit /b 0
if !MAJ! LSS 11 (
    echo [%date% %time%] %CANDIDAT% : version !VER! trop ancienne>>"%LOG%"
    exit /b 0
)
set "JAVABIN=%CANDIDAT%"
echo [%date% %time%] candidat valide : %CANDIDAT% ^(version !VER!^)>>"%LOG%"
exit /b 0
