@echo off
setlocal
cd /d "%~dp0"

echo ============================================
echo  PixelPusher Bridge - Compilation
echo ============================================

echo Arret des instances en cours (elles verrouillent le jar)...
powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='javaw.exe' or Name='java.exe'\" | Where-Object { $_.CommandLine -match 'PixelPusherBridge' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }" >nul 2>nul
rem ping plutot que timeout : timeout echoue des que l'entree standard est
rem redirigee (compilation depuis un script ou une CI), et laissait alors le
rem jar se faire ecraser pendant qu'une JVM le tenait encore.
ping -n 3 127.0.0.1 >nul 2>nul


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
if not errorlevel 1 goto :compilation_ok

rem Le repli sans --release ne doit servir QUE si le JDK refuse l'option
rem elle-meme. Sur une vraie erreur de compilation on s'arrete net : sinon
rem on fabriquerait un jar dont le bytecode cible la version du JDK local,
rem qui refuserait de demarrer sur un poste equipe d'un Java 11.
rem
rem Les sorties en erreur passent par une etiquette et non par un "exit /b 1"
rem place dans un bloc parenthese imbrique : dans ce cas cmd.exe termine bien le
rem script mais PERD le code de retour, qui vaut alors 0. Une CI, un wrapper
rem PowerShell ou un "cmd /c BUILD.bat" croiraient a un build reussi alors
rem qu'aucun jar n'a ete produit (verifie sur cette machine).
findstr /I /C:"--release" /C:"release version" /C:"invalid target release" build\javac_err.txt >nul
if errorlevel 1 goto :erreur_compilation

echo Option --release 11 refusee par ce JDK, nouvel essai en mode standard...
echo La version reelle du bytecode sera verifiee juste apres.
javac -encoding UTF-8 -d build\classes @sources_list.txt 2>build\javac_err.txt
if errorlevel 1 goto :erreur_compilation

:compilation_ok
if exist build\javac_err.txt type build\javac_err.txt

rem Garde-fou : on relit l'en-tete d'une classe compilee (octets 6-7 = version
rem majeure du bytecode). 55 = Java 11. Sans ce controle, un repli ou un futur
rem JDK produirait en silence un jar illisible sur la machine de spectacle.
echo Verification de la version du bytecode ^(Java 11 = 55^)...
powershell -NoProfile -Command "$f='build\classes\com\pixelpusher\bridge\Main.class'; if (-not (Test-Path $f)) { Write-Host 'Main.class introuvable'; exit 1 }; $b=[IO.File]::ReadAllBytes($f); $m=$b[6]*256+$b[7]; if ($m -ne 55) { Write-Host ('Bytecode en version ' + $m + ' au lieu de 55'); exit 1 }; exit 0"
if errorlevel 1 (
    echo.
    echo ERREUR : le bytecode produit ne cible pas Java 11.
    echo Le jar ne demarrerait pas sur un poste equipe d'un Java plus ancien.
    pause
    exit /b 1
)

echo Integration de l'interface web...
mkdir build\classes\web
copy /Y web\*.html build\classes\web\ >nul
if errorlevel 1 (
    echo.
    echo ERREUR : copie de web\*.html impossible - le jar serait sans interface.
    pause
    exit /b 1
)

rem La licence MIT impose que la notice accompagne toute copie du logiciel :
rem on l'embarque dans le jar, puis a cote des binaires livres.
mkdir build\classes\META-INF
copy /Y LICENSE build\classes\META-INF\LICENSE >nul
if errorlevel 1 echo ATTENTION : LICENSE introuvable, le jar partira sans notice de licence.

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
copy /Y LICENSE "dist\PixelPusher Bridge (Windows)\" >nul
if errorlevel 1 echo ATTENTION : LICENSE non copie dans le dossier Windows.

rem Met aussi a jour le jar dans l'app macOS si elle a deja ete assemblee
if exist "dist\PixelPusher Bridge.app\Contents\Resources" (
    copy /Y dist\PixelPusherBridge.jar "dist\PixelPusher Bridge.app\Contents\Resources\" >nul
    echo App macOS mise a jour ^(jar uniquement^).
)

rem Controle de coherence des versions : la convention du projet exige la meme
rem valeur dans AppConfig.VERSION, les deux entrees de Info.plist et CHANGELOG.
rem Simple avertissement : ce n'est pas une raison de rater un build. Le try/catch
rem evite qu'un fichier deplace ou renomme fasse cracher une trace .NET rouge en
rem plein build reussi.
powershell -NoProfile -Command "try { $v=[regex]::Match([IO.File]::ReadAllText('src\com\pixelpusher\bridge\AppConfig.java'),'VERSION\s*=\s*.(\d+\.\d+\.\d+)').Groups[1].Value; if (-not $v) { exit 0 }; $ok=$true; foreach ($m in [regex]::Matches([IO.File]::ReadAllText('packaging\macos\Info.plist'),'<string>(\d+\.\d+\.\d+)</string>')) { if ($m.Groups[1].Value -ne $v) { $ok=$false } }; if (-not ([IO.File]::ReadAllText('CHANGELOG.md')).Contains('[' + $v + ']')) { $ok=$false }; if ($ok) { Write-Host ('Version ' + $v + ' : AppConfig, Info.plist et CHANGELOG concordent.') } else { Write-Host ('ATTENTION : la version ' + $v + ' de AppConfig.java ne figure pas a l identique dans packaging\macos\Info.plist et/ou CHANGELOG.md') } } catch { Write-Host 'ATTENTION : controle de coherence des versions impossible (fichier introuvable ?).' }"

echo.
echo ============================================
echo  OK ! Fichiers generes dans le dossier dist\
for %%A in (dist\PixelPusherBridge.jar) do echo   - PixelPusherBridge.jar ^(%%~zA octets^)
echo   - PixelPusher Bridge ^(Windows^)\ : jar + lanceurs + LICENSE
if exist "dist\PixelPusher Bridge.app" echo   - PixelPusher Bridge.app : jar rafraichi
echo.
echo Rappel : le zip macOS livrable doit etre regenere sous macOS ou Linux avec
echo packaging/make_mac_app.sh, qui reassemble l'app complete ^(lanceur, Info.plist,
echo icone, jar, LICENSE^). Un zip fabrique sous Windows perd le bit executable du
echo lanceur et l'app ne demarre pas.
echo ============================================
endlocal
pause
exit /b 0

rem Sortie en erreur commune a la compilation. Elle vit au premier niveau du
rem script pour que "exit /b 1" remonte reellement au processus appelant.
:erreur_compilation
echo.
echo ERREUR DE COMPILATION :
if exist build\javac_err.txt type build\javac_err.txt
echo.
echo ^(details egalement dans build\javac_err.txt^)
endlocal
pause
exit /b 1
