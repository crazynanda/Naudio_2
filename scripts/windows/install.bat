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

echo [1/4] Verifying Naudio security certificate in LocalMachine\TrustedPeople ...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$ErrorActionPreference = 'Stop'; " ^
    "try { " ^
    "  $cert = New-Object System.Security.Cryptography.X509Certificates.X509Certificate2('%~dp0simpmusic.crt'); " ^
    "  $targetThumb = $cert.Thumbprint.ToUpperInvariant(); " ^
    "  $store = New-Object System.Security.Cryptography.X509Certificates.X509Store('TrustedPeople', 'LocalMachine'); " ^
    "  $store.Open('ReadOnly'); " ^
    "  $found = $false; " ^
    "  foreach ($c in $store.Certificates) { " ^
    "    if ($c.Thumbprint.ToUpperInvariant() -eq $targetThumb) { $found = $true; break } " ^
    "  }; " ^
    "  $store.Close(); " ^
    "  if (-not $found) { " ^
    "    $null = & certutil.exe -verifystore 'TrustedPeople' $targetThumb 2>&1; " ^
    "    if ($LASTEXITCODE -eq 0) { $found = $true } " ^
    "  }; " ^
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
        "  $targetThumb = $cert.Thumbprint.ToUpperInvariant(); " ^
        "  $store = New-Object System.Security.Cryptography.X509Certificates.X509Store('TrustedPeople', 'LocalMachine'); " ^
        "  $store.Open('ReadOnly'); " ^
        "  $found = $false; " ^
        "  foreach ($c in $store.Certificates) { " ^
        "    if ($c.Thumbprint.ToUpperInvariant() -eq $targetThumb) { $found = $true; break } " ^
        "  }; " ^
        "  $store.Close(); " ^
        "  if (-not $found) { " ^
        "    $null = & certutil.exe -verifystore 'TrustedPeople' $targetThumb 2>&1; " ^
        "    if ($LASTEXITCODE -eq 0) { $found = $true } " ^
        "  }; " ^
        "  if ($found) { exit 0 } else { exit 1 } " ^
        "} catch { exit 1 }"
    if %errorlevel% neq 0 (
        echo [ERROR] Failed to trust the Naudio certificate in TrustedPeople.
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
echo [2/4] Version pre-flight check ...
REM Windows refuses to install a package whose identity equals the installed
REM one while its version is not strictly higher (error 0x80073CFB), and an
REM equal-or-lower version would silently downgrade. Read the version out of
REM the bundled MSIX's manifest, compare it with the installed Naudio, and
REM refuse BEFORE anything is touched. A failed or refused pre-check never
REM installs, removes or modifies anything.
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$ErrorActionPreference = 'Stop'; " ^
    "try { " ^
    "  Add-Type -AssemblyName System.IO.Compression.FileSystem; " ^
    "  $zip = [System.IO.Compression.ZipFile]::OpenRead('%MSIX%'); " ^
    "  $reader = New-Object System.IO.StreamReader($zip.GetEntry('AppxManifest.xml').Open()); " ^
    "  $manifest = $reader.ReadToEnd(); $reader.Close(); $zip.Dispose(); " ^
    "  $match = [regex]::Match($manifest, 'Version=.([0-9.]+).'); " ^
    "  if (-not $match.Success) { Write-Host '[ERROR] The bundled package manifest carries no Version. Nothing was changed.'; exit 3 } " ^
    "  $incoming = [version]$match.Groups[1].Value; " ^
    "  $installed = Get-AppxPackage -Name 'Simpmusic' | Sort-Object -Property { [version]$_.Version } -Descending | Select-Object -First 1; " ^
    "  Write-Host ('Incoming package version : ' + $incoming); " ^
    "  if ($installed) { " ^
    "    Write-Host ('Currently installed      : ' + $installed.Version); " ^
    "    if ($incoming -le [version]$installed.Version) { " ^
    "      Write-Host '[ERROR] The bundled package is not newer than the installed Naudio. Nothing was installed or removed; download a newer build.'; exit 3 } " ^
    "  } else { Write-Host 'No existing Naudio installation found.' } " ^
    "} catch { " ^
    "  Write-Host ('[ERROR] The version pre-check could not run: ' + $_.Exception.Message); exit 4 }"
set "PRECHECK_STATUS=%errorlevel%"
if "%PRECHECK_STATUS%"=="3" (
    echo Installation refused by the version pre-check. Nothing was changed.
    pause
    exit /b 3
)
if "%PRECHECK_STATUS%"=="4" (
    echo The version pre-check failed to run, so the installation was refused. Nothing was changed.
    pause
    exit /b 4
)

echo [3/4] Installing/updating MSIX for current user: "%MSIX%"
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
echo [4/4] Launching Naudio ...
for /f "usebackq tokens=*" %%i in (`powershell -NoProfile -Command "$pkg = Get-AppxPackage -Name 'Simpmusic' | Where-Object { $_.Publisher -eq 'CN=Naudio' -and $_.PackageFamilyName -eq 'Simpmusic_yhxqqzq5790tp' } | Sort-Object -Property { [version]$_.Version } -Descending | Select-Object -First 1; if ($pkg) { $appId = (Get-AppxPackageManifest $pkg).Package.Applications.Application.Id; if (-not $appId) { $appId = 'Simpmusic' }; Write-Output ($pkg.PackageFamilyName + '!' + $appId) }"`) do set "AUMID=%%i"
if not defined AUMID set "AUMID=Simpmusic_yhxqqzq5790tp!Simpmusic"

start "" "shell:appsFolder\%AUMID%"
if %errorlevel% neq 0 (
    echo [INFO] Could not launch Naudio automatically.
    echo        You can start Naudio from the Start Menu or run:
    echo        shell:appsFolder\%AUMID%
)

echo.
echo Naudio installed successfully.
echo To uninstall: Settings -^> Apps -^> Installed apps -^> Naudio -^> Uninstall
echo.
pause
endlocal
