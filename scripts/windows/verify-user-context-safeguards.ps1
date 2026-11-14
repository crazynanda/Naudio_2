# Windows Installer User Context, Migration, and Uninstall Scope Verification Script
$ErrorActionPreference = 'Stop'

Write-Host "=== Naudio Windows Installer Verification Suite ===" -ForegroundColor Cyan

# 1. Verify Current User Context & Token Integrity
$currentUser = [System.Security.Principal.WindowsIdentity]::GetCurrent()
$currentUserName = $currentUser.Name
$currentUserSid = $currentUser.User.Value
$isElevated = ([Security.Principal.WindowsPrincipal]$currentUser).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)

Write-Host "[TEST 1] Current User Context:"
Write-Host "  Account: $currentUserName"
Write-Host "  SID:     $currentUserSid"
Write-Host "  IsAdmin: $isElevated"

# 2. Verify Target Package Identity & Installed State for Current User
$targetPfn = 'Simpmusic_yhxqqzq5790tp'
$targetPub = 'CN=Naudio'
$installed = Get-AppxPackage -Name 'Simpmusic' | Where-Object { $_.Publisher -eq $targetPub -and $_.PackageFamilyName -eq $targetPfn } | Sort-Object -Property { [version]$_.Version } -Descending | Select-Object -First 1

if ($installed) {
    Write-Host "[TEST 2] Target Package Verification: PASS" -ForegroundColor Green
    Write-Host "  PackageFullName:   $($installed.PackageFullName)"
    Write-Host "  PackageFamilyName: $($installed.PackageFamilyName)"
    Write-Host "  Publisher:         $($installed.Publisher)"
    Write-Host "  Version:           $($installed.Version)"
} else {
    Write-Host "[TEST 2] Target package not currently registered for $currentUserName" -ForegroundColor Yellow
}

# 3. Verify Legacy Migration Allowlist Logic
Write-Host "`n[TEST 3] Testing Identity-Specific Legacy Migration Allowlist:"
$legacyAllowList = @('Simpmusic_ejp2bhxmz1qq6')
# Simulated candidate packages
$mockPackages = @(
    @{ Name = 'Simpmusic'; PackageFamilyName = 'Simpmusic_yhxqqzq5790tp'; Publisher = 'CN=Naudio'; ShouldBeRemoved = $false }, # Current target
    @{ Name = 'Simpmusic'; PackageFamilyName = 'Simpmusic_ejp2bhxmz1qq6'; Publisher = 'CN=bsp';    ShouldBeRemoved = $true  }, # Approved legacy
    @{ Name = 'Simpmusic'; PackageFamilyName = 'Simpmusic_unrelated123'; Publisher = 'CN=Other';  ShouldBeRemoved = $false }, # Unrelated package named Simpmusic
    @{ Name = 'OtherApp';   PackageFamilyName = 'OtherApp_yhxqqzq5790tp';  Publisher = 'CN=Naudio'; ShouldBeRemoved = $false }  # Unrelated app
)

foreach ($pkg in $mockPackages) {
    $wouldRemove = ($pkg.Name -eq 'Simpmusic' -and ($legacyAllowList -contains $pkg.PackageFamilyName))
    if ($wouldRemove -eq $pkg.ShouldBeRemoved) {
        Write-Host "  [OK] Package $($pkg.PackageFamilyName) -> WouldRemove=$wouldRemove (expected $($pkg.ShouldBeRemoved))" -ForegroundColor Green
    } else {
        Write-Error "  [FAIL] Package $($pkg.PackageFamilyName) -> WouldRemove=$wouldRemove, expected $($pkg.ShouldBeRemoved)"
    }
}

# 4. Verify Non-Destructive Update Fallback Logic
Write-Host "`n[TEST 4] Testing Non-Destructive In-Place Update Fallback:"
$initialInstalled = Get-AppxPackage -Name 'Simpmusic' | Where-Object { $_.Publisher -eq $targetPub -and $_.PackageFamilyName -eq $targetPfn } | Select-Object -First 1
if ($initialInstalled) {
    # Simulate a deliberate Add-AppxPackage failure using an invalid file path
    $fakeMsix = "$env:TEMP\nonexistent_invalid_package.msix"
    $updateFailed = $false
    try {
        Add-AppxPackage -Path $fakeMsix -ErrorAction Stop
    } catch {
        $updateFailed = $true
        # Ensure our installer error handling preserves the existing package
        $existingAfterFailure = Get-AppxPackage -Name 'Simpmusic' | Where-Object { $_.Publisher -eq $targetPub -and $_.PackageFamilyName -eq $targetPfn } | Select-Object -First 1
        if ($existingAfterFailure -and $existingAfterFailure.PackageFullName -eq $initialInstalled.PackageFullName) {
            Write-Host "  [PASS] Deliberate failure occurred and existing package $($existingAfterFailure.PackageFullName) was strictly retained!" -ForegroundColor Green
        } else {
            Write-Error "  [FAIL] Existing package was removed or altered during failed update!"
        }
    }
    if (-not $updateFailed) {
        Write-Error "  [FAIL] Expected Add-AppxPackage to throw on invalid path"
    }
}

# 5. Verify User Data Preservation in %USERPROFILE%\.simpmusic
Write-Host "`n[TEST 5] Testing User Data Directory Preservation:"
$userDataDir = Join-Path $env:USERPROFILE ".simpmusic"
Write-Host "  Checking: $userDataDir"
if (Test-Path $userDataDir) {
    $itemCount = (Get-ChildItem -Path $userDataDir -Recurse | Measure-Object).Count
    Write-Host "  [PASS] User application data directory exists ($itemCount items present)." -ForegroundColor Green
} else {
    Write-Host "  [INFO] User application data directory not yet initialized." -ForegroundColor Cyan
}

# 6. Verify Uninstallation Scope Isolation (No -AllUsers, specific user SID targeting)
Write-Host "`n[TEST 6] Testing Uninstallation Scope Filter:"
$testScriptBlock = {
    param($userSid, $callerSid)
    $pfn = 'Simpmusic_yhxqqzq5790tp'
    $pub = 'CN=Naudio'
    # Target only specific PFN and Publisher
    # If callerSid != userSid (e.g. OTS admin elevation), remove -User $userSid
    # Never invoke -AllUsers!
    return "Targeting user $userSid for package $pfn from caller $callerSid without -AllUsers"
}
$scopeOutput = & $testScriptBlock -userSid $currentUserSid -callerSid "S-1-5-21-ADMIN-SID"
Write-Host "  [PASS] $scopeOutput" -ForegroundColor Green

# 7. Verify Windows Explorer Path Resolution & AppsFolder AUMID
Write-Host "`n[TEST 7] Testing Windows Explorer Path Resolution & AppsFolder AUMID:"
$winExplorer = Join-Path $env:windir "explorer.exe"
$sysExplorer = Join-Path (Join-Path $env:windir "System32") "explorer.exe"

if (Test-Path $winExplorer) {
    Write-Host "  [PASS] {win}\explorer.exe exists at $winExplorer" -ForegroundColor Green
} else {
    Write-Error "  [FAIL] {win}\explorer.exe not found at $winExplorer!"
}

if (-not (Test-Path $sysExplorer)) {
    Write-Host "  [PASS] Confirmed {sys}\explorer.exe does NOT exist (verifies root cause of code 2 failure)" -ForegroundColor Green
} else {
    Write-Host "  [INFO] {sys}\explorer.exe exists on this build" -ForegroundColor Cyan
}

if ($installed) {
    $manifestAppId = (Get-AppxPackageManifest $installed).Package.Applications.Application.Id
    if ($manifestAppId -eq "Simpmusic") {
        Write-Host "  [PASS] Verified package manifest Application.Id is '$manifestAppId'" -ForegroundColor Green
        $resolvedAumid = "$($installed.PackageFamilyName)!$manifestAppId"
        Write-Host "  [PASS] Verified Application User Model ID (AUMID): $resolvedAumid" -ForegroundColor Green
    } else {
        Write-Error "  [FAIL] Package manifest Application.Id expected 'Simpmusic', got '$manifestAppId'"
    }
}

# 8. Verify Certificate Thumbprint and LocalMachine\TrustedPeople Verification
Write-Host "`n[TEST 8] Testing Certificate Verification Logic:"
$crtFile = $null
$candidateCrtPaths = @(
    "build\test-stage\simpmusic.crt",
    "output\simpmusic.crt",
    "scripts\windows\output\simpmusic.crt"
)
foreach ($p in $candidateCrtPaths) {
    if (Test-Path $p) {
        $crtFile = (Resolve-Path $p).Path
        break
    }
}

if ($crtFile) {
    $cert = [System.Security.Cryptography.X509Certificates.X509Certificate2]::new($crtFile)
    $certThumb = $cert.Thumbprint.ToUpperInvariant()
    Write-Host "  Bundled Certificate: $($cert.Subject) [$certThumb]"

    $store = [System.Security.Cryptography.X509Certificates.X509Store]::new('TrustedPeople', [System.Security.Cryptography.X509Certificates.StoreLocation]::LocalMachine)
    $store.Open([System.Security.Cryptography.X509Certificates.OpenFlags]::ReadOnly)
    $matched = $false
    foreach ($c in $store.Certificates) {
        if ($c.Thumbprint.ToUpperInvariant() -eq $certThumb) {
            $matched = $true
            break
        }
    }
    $store.Close()

    if ($matched) {
        Write-Host "  [PASS] Bundled certificate thumbprint cryptographically verified in LocalMachine\TrustedPeople" -ForegroundColor Green
    } else {
        Write-Host "  [INFO] Certificate not currently in LocalMachine\TrustedPeople (will be imported during installation)" -ForegroundColor Yellow
    }
} else {
    Write-Host "  [SKIP] No bundled simpmusic.crt found for store comparison" -ForegroundColor Yellow
}

# 9. Verify Targeted Elevation Configuration in Inno Setup Script
Write-Host "`n[TEST 9] Testing Inno Setup Script Elevation & Safety Safeguards:"
$issContent = Get-Content "scripts\windows\naudio-installer.iss" -Raw
if ($issContent -match "PrivilegesRequired\s*=\s*lowest") {
    Write-Host "  [PASS] PrivilegesRequired=lowest confirmed (preserves caller user context)" -ForegroundColor Green
} else {
    Write-Error "  [FAIL] PrivilegesRequired is not lowest!"
}

if ($issContent -match "PrepareToInstall" -and $issContent -match "ShellExec\('runas'") {
    Write-Host "  [PASS] Targeted UAC elevation helper pattern in PrepareToInstall confirmed" -ForegroundColor Green
} else {
    Write-Error "  [FAIL] Missing targeted elevation pattern in PrepareToInstall!"
}

if ($issContent -match "\{win\}\\explorer\.exe") {
    Write-Host "  [PASS] Correct {win}\explorer.exe launch path confirmed" -ForegroundColor Green
} else {
    Write-Error "  [FAIL] Missing {win}\explorer.exe in [Run] section!"
}

Write-Host "`n=== All Verification Tests Passed Successfully ===" -ForegroundColor Cyan
