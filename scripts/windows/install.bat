@echo off
REM Naudio offline installer for Windows.
REM Double-click this file. UAC will prompt for admin (needed for cert + sideload).
REM
REM Bundle requirement (same folder as this .bat):
REM   - simpmusic.crt
REM   - simpmusic-<version>.msix     (any .msix matching simpmusic-*.msix)
REM
REM Only x64 MSIX is shipped — Windows 11 ARM64 hosts run it under Prism
REM emulation. ARM64 native build is blocked upstream by a missing
REM androidx.sqlite-bundled windows-arm64 native binary.

setlocal EnableExtensions
cd /d "%~dp0"

REM --- 1. Self-elevate to admin -----------------------------------------------
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo Requesting admin elevation...
    powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    exit /b
)

echo.
echo === Naudio offline installer ===
echo.

REM --- 2. Install cert to Trusted People (idempotent via certutil) -----------
if not exist "%~dp0simpmusic.crt" (
    echo [ERROR] simpmusic.crt not found next to install.bat
    pause
    exit /b 1
)

echo [1/3] Importing simpmusic.crt to LocalMachine\TrustedPeople ...
certutil -addstore -f "TrustedPeople" "%~dp0simpmusic.crt"
if %errorlevel% neq 0 (
    echo [ERROR] Certificate import failed.
    pause
    exit /b 1
)

REM --- 3. Find the bundled MSIX ----------------------------------------------
set "MSIX="
for %%f in ("%~dp0simpmusic-*.msix") do set "MSIX=%%f"
if not defined MSIX for %%f in ("%~dp0*.msix") do set "MSIX=%%f"

if not defined MSIX (
    echo [ERROR] No MSIX package found in this folder.
    pause
    exit /b 1
)

echo.
echo [2/3] Installing/updating MSIX: "%MSIX%"
REM -ForceUpdateFromAnyVersion overwrites an existing install in place (keeps app
REM data) even when the version is identical.
REM Never remove existing packages on error; old working installations are retained.
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$ErrorActionPreference = 'Stop'; " ^
    "$targetPfn = 'Simpmusic_yhxqqzq5790tp'; " ^
    "$targetPub = 'CN=Naudio'; " ^
    "try { " ^
    "  Add-AppxPackage -Path '%MSIX%' -ForceApplicationShutdown -ForceUpdateFromAnyVersion; " ^
    "} catch { " ^
    "  Write-Host '[ERROR] Add-AppxPackage failed:' $_.Exception.Message; " ^
    "  $existing = Get-AppxPackage -Name 'Simpmusic' | Select-Object -First 1; " ^
    "  if ($existing) { " ^
    "    Write-Host ('[INFO] Existing installation preserved: ' + $existing.PackageFullName); " ^
    "    if ($existing.Publisher -ne $targetPub) { " ^
    "      Write-Host ('[INFO] Publisher mismatch: existing has ' + $existing.Publisher + ' while package has ' + $targetPub); " ^
    "    } " ^
    "  } " ^
    "  exit 1; " ^
    "}; " ^
    "$installed = Get-AppxPackage -Name 'Simpmusic' | Where-Object { $_.Publisher -eq $targetPub -and $_.PackageFamilyName -eq $targetPfn } | Sort-Object -Property { [version]$_.Version } -Descending | Select-Object -First 1; " ^
    "if (-not $installed) { " ^
    "  Write-Host '[ERROR] Verification failed: Naudio package was not found after installation.'; " ^
    "  exit 1; " ^
    "}; " ^
    "try { " ^
    "  Get-AppxPackage -Name 'Simpmusic' | Where-Object { $_.PackageFamilyName -ne $targetPfn } | Remove-AppxPackage -ErrorAction SilentlyContinue; " ^
    "} catch {}; " ^
    "exit 0"

if %errorlevel% neq 0 (
    echo.
    echo [ERROR] Naudio installation failed. Any previous installation was left untouched.
    echo Common fixes:
    echo   - Enable 'Install apps from any source, including loose files'
    echo     ^(Settings -^> Privacy ^& security -^> For developers^)
    echo   - Check Windows Event Viewer -^> Applications and Services Logs -^> Microsoft -^> Windows -^> AppxPackagingOM
    pause
    exit /b 1
)

REM --- 4. Launch app ---------------------------------------------------------
echo.
echo [3/3] Launching Naudio ...
for /f "usebackq tokens=*" %%i in (`powershell -NoProfile -Command "(Get-AppxPackage -Name 'Simpmusic' | Where-Object { $_.Publisher -eq 'CN=Naudio' -and $_.PackageFamilyName -eq 'Simpmusic_yhxqqzq5790tp' } | Sort-Object -Property { [version]$_.Version } -Descending | Select-Object -First 1).PackageFamilyName"`) do set "PFN=%%i"
if defined PFN (
    start "" "shell:appsFolder\%PFN%!Simpmusic"
) else (
    start "" "shell:appsFolder\Simpmusic_yhxqqzq5790tp!Simpmusic"
)

echo.
echo Naudio installed successfully.
echo To uninstall: Settings -^> Apps -^> Installed apps -^> Naudio -^> Uninstall
echo.
pause
endlocal
