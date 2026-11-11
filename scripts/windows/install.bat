@echo off
REM Naudio offline installer for Windows.
REM Double-click this file to install or update Naudio.
REM
REM User Context Preservation:
REM   - The installer runs in the original interactive user context.
REM   - UAC elevation is requested ONLY for importing the code-signing certificate
REM     into LocalMachine\TrustedPeople (if not already trusted).
REM   - Add-AppxPackage and application launch always execute in the caller's account,
REM     even when a separate administrator account provides UAC credentials.
REM
REM Bundle requirement (same folder as this .bat):
REM   - simpmusic.crt
REM   - simpmusic-<version>.msix     (any .msix matching simpmusic-*.msix)
REM
REM Only x64 MSIX is shipped ? Windows 11 ARM64 hosts run it under Prism
REM emulation. ARM64 native build is blocked upstream by a missing
REM androidx.sqlite-bundled windows-arm64 native binary.

setlocal EnableExtensions
cd /d "%~dp0"

echo.
echo ===================================
echo    Naudio Offline Installer
echo ===================================
echo.

REM --- 1. Verify and import security certificate to LocalMachine\TrustedPeople ---
if not exist "%~dp0simpmusic.crt" (
    echo [ERROR] simpmusic.crt not found next to install.bat
    pause
    exit /b 1
)

echo [1/3] Verifying Naudio security certificate in LocalMachine\TrustedPeople ...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$ErrorActionPreference = 'Stop'; " ^
    "try { " ^
    "  $cert = New-Object System.Security.Cryptography.X509Certificates.X509Certificate2('%~dp0simpmusic.crt'); " ^
    "  $found = Get-ChildItem Cert:\LocalMachine\TrustedPeople -ErrorAction SilentlyContinue | Where-Object { $_.Thumbprint -eq $cert.Thumbprint }; " ^
    "  if ($found) { exit 0 } else { exit 2 } " ^
    "} catch { exit 1 }"
set "CERT_STATUS=%errorlevel%"

if %CERT_STATUS% neq 0 (
    echo Certificate not yet in LocalMachine\TrustedPeople. Requesting administrator permission...
    net session >nul 2>&1
    if %errorlevel% equ 0 (
        certutil -addstore -f "TrustedPeople" "%~dp0simpmusic.crt"
    ) else (
        powershell -NoProfile -ExecutionPolicy Bypass -Command ^
            "Start-Process -FilePath 'certutil.exe' -ArgumentList '-addstore -f \"TrustedPeople\" \"%~dp0simpmusic.crt\"' -Verb RunAs -Wait"
    )

    REM Verify the certificate was successfully installed
    powershell -NoProfile -ExecutionPolicy Bypass -Command ^
        "$ErrorActionPreference = 'Stop'; " ^
        "try { " ^
        "  $cert = New-Object System.Security.Cryptography.X509Certificates.X509Certificate2('%~dp0simpmusic.crt'); " ^
        "  $found = Get-ChildItem Cert:\LocalMachine\TrustedPeople -ErrorAction SilentlyContinue | Where-Object { $_.Thumbprint -eq $cert.Thumbprint }; " ^
        "  if ($found) { exit 0 } else { exit 1 } " ^
        "} catch { exit 1 }"
    if %errorlevel% neq 0 (
        echo [ERROR] Failed to trust the Naudio certificate in LocalMachine\TrustedPeople.
        echo         Administrator permission is required to trust the security certificate.
        pause
        exit /b 1
    )
    echo Certificate trusted successfully.
) else (
    echo Security certificate is already trusted.
)

REM --- 2. Find the bundled MSIX package --------------------------------------
set "MSIX="
for %%f in ("%~dp0simpmusic-*.msix") do set "MSIX=%%f"
if not defined MSIX for %%f in ("%~dp0*.msix") do set "MSIX=%%f"

if not defined MSIX (
    echo [ERROR] No MSIX package found in this folder.
    pause
    exit /b 1
)

echo.
echo [2/3] Installing/updating MSIX for current user: "%MSIX%"
REM Add-AppxPackage runs in the caller's user context to register Naudio for this user.
REM -ForceUpdateFromAnyVersion ensures in-place upgrade preserving user data.
REM Legacy migration allowlist: only approved legacy package family (Simpmusic_ejp2bhxmz1qq6) is retired.
REM Existing installations are never uninstalled on error.
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$ErrorActionPreference = 'Stop'; " ^
    "$targetPfn = 'Simpmusic_yhxqqzq5790tp'; " ^
    "$targetPub = 'CN=Naudio'; " ^
    "$legacyAllowList = @('Simpmusic_ejp2bhxmz1qq6'); " ^
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
    "  Get-AppxPackage -Name 'Simpmusic' | Where-Object { $legacyAllowList -contains $_.PackageFamilyName } | Remove-AppxPackage -ErrorAction SilentlyContinue; " ^
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

REM --- 3. Launch app in current user session ---------------------------------
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
