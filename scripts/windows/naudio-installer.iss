; Naudio Windows Standalone Setup Script (Inno Setup 6+)
; Produces Naudio-Setup-x64.exe bundling the signed x64 MSIX and certificate.

#ifndef AppVersion
  #define AppVersion "2.3.4"
#endif

#ifndef SourceDir
  #define SourceDir "..\..\output"
#endif

#ifndef OutputDir
  #define OutputDir "..\..\output"
#endif

[Setup]
; Unique GUID for Naudio Setup
AppId={{D37E848B-27B1-4A59-B488-B31F7B01A94E}
AppName=Naudio
AppVersion={#AppVersion}
AppVerName=Naudio {#AppVersion}
AppPublisher=Nanda Kumar
AppPublisherURL=https://github.com/crazynanda/Naudio_2
AppSupportURL=https://github.com/crazynanda/Naudio_2/issues
AppUpdatesURL=https://github.com/crazynanda/Naudio_2/releases
DefaultDirName={autopf}\Naudio
DisableDirPage=yes
DisableProgramGroupPage=yes
OutputBaseFilename=Naudio-Setup-x64
OutputDir={#OutputDir}
Compression=lzma2/max
SolidCompression=yes
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
; Runs unprivileged by default to preserve the caller's Windows user context for MSIX deployment.
; Elevates only targeted operations (certificate import to LocalMachine\TrustedPeople).
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog commandline
SetupIconFile=..\..\composeApp\icon\circle_app_icon.ico
UninstallDisplayName=Naudio
UninstallDisplayIcon={app}\circle_app_icon.ico
WizardStyle=modern
ShowLanguageDialog=no
CloseApplications=yes

[Files]
; Public certificate and signed MSIX package extracted to temporary directory
Source: "{#SourceDir}\simpmusic.crt"; DestDir: "{tmp}"; Flags: deleteafterinstall
Source: "{#SourceDir}\simpmusic-*.msix"; DestDir: "{tmp}"; Flags: deleteafterinstall
Source: "..\..\composeApp\icon\circle_app_icon.ico"; DestDir: "{app}"; Flags: ignoreversion

[Run]
Filename: "{sys}\explorer.exe"; Parameters: "shell:appsFolder\{code:GetInstalledAumid}"; Description: "{cm:LaunchProgram,Naudio}"; Flags: nowait postinstall skipifsilent runasoriginaluser

[UninstallRun]
; Targeted per-user uninstallation: removes only the verified package (CN=Naudio / Simpmusic_yhxqqzq5790tp)
; for the intended user account without touching other user profiles or unrelated packages. Never uses -AllUsers.
Filename: "powershell.exe"; Parameters: "-NoProfile -NonInteractive -ExecutionPolicy Bypass -Command ""$pfn = 'Simpmusic_yhxqqzq5790tp'; $pub = 'CN=Naudio'; $sidFile = '{app}\install_user.sid'; $targetSid = $null; if (Test-Path $sidFile) {{ $targetSid = (Get-Content $sidFile -Raw).Trim() }}; Get-AppxPackage -Name 'Simpmusic' | Where-Object {{ $_.Publisher -eq $pub -and $_.PackageFamilyName -eq $pfn } | Remove-AppxPackage -ErrorAction SilentlyContinue; if ($targetSid -and $targetSid -ne [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value) {{ try {{ $targetPkg = Get-AppxPackage -Name 'Simpmusic' -User $targetSid -ErrorAction SilentlyContinue | Where-Object {{ $_.Publisher -eq $pub -and $_.PackageFamilyName -eq $pfn }}; if ($targetPkg) {{ Remove-AppxPackage -Package $targetPkg.PackageFullName -User $targetSid -ErrorAction SilentlyContinue }} }} catch {{}} }}"""; Flags: runhidden; RunOnceId: "UninstallNaudioMsixPackage"

[Code]
var
  InstalledAumid: String;

function GetInstalledAumid(Param: String): String;
begin
  if Trim(InstalledAumid) <> '' then
    Result := InstalledAumid
  else
    Result := 'Simpmusic_yhxqqzq5790tp!Simpmusic';
end;

function RunCommandWithLog(Executable, Parameters, LogFile: String): Integer;
var
  CmdLine: String;
  ResultCode: Integer;
begin
  CmdLine := '/c ""' + Executable + '" ' + Parameters + ' > "' + LogFile + '" 2>&1"';
  if Exec(ExpandConstant('{cmd}'), CmdLine, '', SW_HIDE, ewWaitUntilTerminated, ResultCode) then
    Result := ResultCode
  else
    Result := -1;
end;

function IsCertInstalled(CertFile: String): Boolean;
var
  CheckPs: String;
  ResultCode: Integer;
begin
  CheckPs :=
    '$ErrorActionPreference = ''Stop''; ' +
    'try { ' +
    '  $cert = New-Object System.Security.Cryptography.X509Certificates.X509Certificate2(''' + CertFile + '''); ' +
    '  $found = Get-ChildItem Cert:\LocalMachine\TrustedPeople -ErrorAction SilentlyContinue | Where-Object { $_.Thumbprint -eq $cert.Thumbprint }; ' +
    '  if ($found) { exit 0 } else { exit 2 } ' +
    '} catch { exit 1 }';
  ResultCode := -1;
  if Exec('powershell.exe', '-NoProfile -NonInteractive -ExecutionPolicy Bypass -Command "' + CheckPs + '"', '', SW_HIDE, ewWaitUntilTerminated, ResultCode) then
    Result := (ResultCode = 0)
  else
    Result := False;
end;

function ExecInUserContext(Executable, Parameters, WorkingDir: String; ShowCmd: Integer; Wait: TExecWait; var ResultCode: Integer): Boolean;
begin
  // If setup was elevated (or started with "Run as administrator"), attempt to execute as original interactive user
  if IsAdminInstallMode then
  begin
    if ExecAsOriginalUser(Executable, Parameters, WorkingDir, ShowCmd, Wait, ResultCode) then
    begin
      Result := True;
      Exit;
    end;
  end;
  // Standard user context execution
  Result := Exec(Executable, Parameters, WorkingDir, ShowCmd, Wait, ResultCode);
end;

procedure CurStepChanged(CurStep: TSetupStep);
var
  CertFile, MsixFile, LogFile, ErrorMsg, PsCommand, AumidLog, SidFile: String;
  ErrorAnsi: AnsiString;
  ResultCode: Integer;
  FindRec: TFindRec;
begin
  if CurStep = ssPostInstall then
  begin
    CertFile := ExpandConstant('{tmp}\simpmusic.crt');
    LogFile := ExpandConstant('{tmp}\naudio_install.log');
    AumidLog := ExpandConstant('{tmp}\naudio_aumid.log');
    SidFile := ExpandConstant('{app}\install_user.sid');

    // Locate the extracted MSIX package
    MsixFile := '';
    if FindFirst(ExpandConstant('{tmp}\*.msix'), FindRec) then
    begin
      try
        MsixFile := ExpandConstant('{tmp}\') + FindRec.Name;
      finally
        FindClose(FindRec);
      end;
    end;

    if (MsixFile = '') or not FileExists(MsixFile) then
    begin
      MsgBox('Failed to locate bundled Naudio MSIX package in temporary directory.', mbError, MB_OK);
      RaiseException('MSIX package not found.');
    end;

    // 1. Certificate verification and targeted elevation into LocalMachine\TrustedPeople store
    WizardForm.StatusLabel.Caption := 'Verifying Naudio security certificate in LocalMachine\TrustedPeople...';

    if not IsCertInstalled(CertFile) then
    begin
      WizardForm.StatusLabel.Caption := 'Importing Naudio security certificate into LocalMachine\TrustedPeople...';

      if IsAdminInstallMode then
      begin
        ResultCode := RunCommandWithLog('certutil.exe', '-addstore -f "TrustedPeople" "' + CertFile + '"', LogFile);
      end
      else
      begin
        // Elevate ONLY certificate installation using UAC "runas"
        ResultCode := -1;
        if not ShellExec('runas', 'certutil.exe', '-addstore -f "TrustedPeople" "' + CertFile + '"', '', SW_HIDE, ewWaitUntilTerminated, ResultCode) then
          ResultCode := -1;
      end;

      if (ResultCode <> 0) or not IsCertInstalled(CertFile) then
      begin
        ErrorMsg := '';
        if FileExists(LogFile) then
        begin
          if LoadStringFromFile(LogFile, ErrorAnsi) then
            ErrorMsg := String(ErrorAnsi);
        end;

        MsgBox('Failed to import the Naudio signing certificate into LocalMachine\TrustedPeople.' + #13#10#13#10 +
               'Exit Code: ' + IntToStr(ResultCode) + #13#10 +
               'Details: ' + Trim(ErrorMsg) + #13#10#13#10 +
               'Administrator privileges are required to trust the security certificate for installation.', mbError, MB_OK);
        RaiseException('Certificate import failed.');
      end;
    end;

    WizardForm.StatusLabel.Caption := 'Installing Naudio MSIX package...';

    // 2. Install / Update MSIX package in original interactive user context
    // -ForceApplicationShutdown closes running instances if upgrading
    // -ForceUpdateFromAnyVersion ensures in-place upgrade preserving user data
    // Identity-specific allowlist: only approved legacy package family (Simpmusic_ejp2bhxmz1qq6) is retired
    PsCommand :=
      '$ErrorActionPreference = ''Stop''; ' +
      '$targetPfn = ''Simpmusic_yhxqqzq5790tp''; ' +
      '$targetPub = ''CN=Naudio''; ' +
      '$legacyAllowList = @(''Simpmusic_ejp2bhxmz1qq6''); ' +
      'try { ' +
      '  $currentSid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value; ' +
      '  [System.IO.File]::WriteAllText(''' + SidFile + ''', $currentSid); ' +
      '} catch {}; ' +
      'try { ' +
      '  Add-AppxPackage -Path ''' + MsixFile + ''' -ForceApplicationShutdown -ForceUpdateFromAnyVersion; ' +
      '} catch { ' +
      '  $err = $_.Exception.Message; ' +
      '  $existing = Get-AppxPackage -Name ''Simpmusic'' | Select-Object -First 1; ' +
      '  $msg = ''Add-AppxPackage failed: '' + $err; ' +
      '  if ($existing) { ' +
      '    $msg += ''`n[INFO] Existing installation preserved: '' + $existing.PackageFullName; ' +
      '    if ($existing.Publisher -ne $targetPub) { ' +
      '      $msg += ''`n[INFO] Publisher mismatch: existing has '' + $existing.Publisher + '' while installer packages '' + $targetPub; ' +
      '    } ' +
      '  } ' +
      '  [System.IO.File]::WriteAllText(''' + LogFile + ''', $msg); ' +
      '  exit 1; ' +
      '}; ' +
      '$installed = Get-AppxPackage -Name ''Simpmusic'' | Where-Object { $_.Publisher -eq $targetPub -and $_.PackageFamilyName -eq $targetPfn } | Sort-Object -Property { [version]$_.Version } -Descending | Select-Object -First 1; ' +
      'if (-not $installed) { ' +
      '  [System.IO.File]::WriteAllText(''' + LogFile + ''', ''Verification failed: Naudio package was not found after installation.''); ' +
      '  exit 1; ' +
      '}; ' +
      'try { ' +
      '  Get-AppxPackage -Name ''Simpmusic'' | Where-Object { $legacyAllowList -contains $_.PackageFamilyName } | Remove-AppxPackage -ErrorAction SilentlyContinue; ' +
      '} catch {}; ' +
      '[System.IO.File]::WriteAllText(''' + AumidLog + ''', ($installed.PackageFamilyName + ''!Simpmusic'')); ' +
      'exit 0;';

    ResultCode := -1;
    if not ExecInUserContext('powershell.exe', '-NoProfile -NonInteractive -ExecutionPolicy Bypass -Command "' + PsCommand + '"', '', SW_HIDE, ewWaitUntilTerminated, ResultCode) or (ResultCode <> 0) then
    begin
      ErrorMsg := '';
      if FileExists(LogFile) then
      begin
        if LoadStringFromFile(LogFile, ErrorAnsi) then
          ErrorMsg := String(ErrorAnsi);
      end;

      if Trim(ErrorMsg) = '' then
        ErrorMsg := 'PowerShell Add-AppxPackage returned exit code ' + IntToStr(ResultCode);

      MsgBox('Failed to install Naudio MSIX package.' + #13#10#13#10 +
             'Details: ' + Trim(ErrorMsg) + #13#10#13#10 +
             'Common solutions:' + #13#10 +
             '  - Ensure Developer Mode / Sideloading is allowed in Windows Settings.' + #13#10 +
             '  - Check Windows Event Viewer -> Applications and Services Logs -> Microsoft -> Windows -> AppxPackagingOM.', mbError, MB_OK);
      RaiseException('MSIX package installation failed.');
    end;

    // Read the recorded AUMID if available
    if FileExists(AumidLog) then
    begin
      if LoadStringFromFile(AumidLog, ErrorAnsi) then
        InstalledAumid := Trim(String(ErrorAnsi));
    end;
  end;
end;
