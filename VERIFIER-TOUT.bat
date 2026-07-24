@echo off
rem ============================================================
rem  Verification complete avant un spectacle ou une publication.
rem
rem  1. Compile et fabrique le jar
rem  2. Banc de tests (invariants de code) + interfaces web + QR
rem  3. Test de bout en bout : reseau -> mapping -> trames -> LED
rem
rem  Si cette commande finit en vert, la chaine complete a ete
rem  verifiee sans avoir besoin du moindre materiel.
rem ============================================================
setlocal
cd /d "%~dp0"

echo.
echo ############################################################
echo #  1/3  COMPILATION
echo ############################################################
call "%~dp0BUILD.bat"
if errorlevel 1 (
    echo.
    echo La compilation a echoue. On s'arrete ici.
    exit /b 1
)

echo.
echo ############################################################
echo #  2/3  BANC DE TESTS
echo ############################################################
call "%~dp0RUN-TESTS.bat"
if errorlevel 1 (
    echo.
    echo Des tests ont echoue. Ne publie pas cette version.
    exit /b 1
)

echo.
echo ############################################################
echo #  3/3  TEST DE BOUT EN BOUT
echo ############################################################
where python >nul 2>nul
if errorlevel 1 (
    echo Python introuvable : test de bout en bout ignore.
    echo Installe Python 3 pour verifier la chaine complete.
    goto fin
)
python tools\smoke_test.py
if errorlevel 1 (
    echo.
    echo Le test de bout en bout a echoue. Ne publie pas cette version.
    exit /b 1
)

:fin
echo.
echo ############################################################
echo #  TOUT EST VERT
echo ############################################################
exit /b 0
