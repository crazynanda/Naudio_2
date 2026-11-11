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
PrivilegesRequired=admin
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
Filename: "{sys}\explorer.exe"; Parameters: "shell:appsFolder\{code:GetInstalledAumid}"; Description: "{cm:LaunchProgram,Naudio}"; Flags: nowait postinstall skipifsilent

[UninstallRun]
Filename: "powershell.exe"; Parameters: "-NoProfile -NonInteractive -ExecutionPolicy Bypass -Command ""$pfn = 'Simpmusic_yhxqqzq5790tp'; $pub = 'CN=Naudio'; Get-AppxPackage -Name 'Simpmusic' | Where-Object {{ $_.Publisher -eq $pub -and $_.PackageFamilyName -eq $pfn } | Remove-AppxPackage -ErrorAction SilentlyContinue; try {{ Get-AppxPackage -Name 'Simpmusic' -AllUsers -ErrorAction SilentlyContinue | Where-Object {{ $_.Publisher -eq $pub -and $_.PackageFamilyName -eq $pfn } | ForEach-Object {{ Remove-AppxPackage -AllUsers -Package $_.PackageFullName -ErrorAction SilentlyContinue } } catch {{}}"""; Flags: runhidden; RunOnceId: "UninstallNaudioMsixPackage"

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

procedure CurStepChanged(CurStep: TSetupStep);
var
  CertFile, MsixFile, LogFile, ErrorMsg, PsCommand, AumidLog: String;
  ErrorAnsi: AnsiString;
  ResultCode: Integer;
  FindRec: TFindRec;
begin
  if CurStep = ssPostInstall then
  begin
    CertFile := ExpandConstant('{tmp}\simpmusic.crt');
    LogFile := ExpandConstant('{tmp}\naudio_install.log');
    AumidLog := ExpandConstant('{tmp}\naudio_aumid.log');

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

    WizardForm.StatusLabel.Caption := 'Importing Naudio security certificate into LocalMachine\TrustedPeople...';

    // 1. Import certificate into LocalMachine\TrustedPeople store
    ResultCode := RunCommandWithLog('certutil.exe', '-addstore -f "TrustedPeople" "' + CertFile + '"', LogFile);
    if ResultCode <> 0 then
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
             'Please verify that Administrator privileges were granted.', mbError, MB_OK);
      RaiseException('Certificate import failed.');
    end;

    WizardForm.StatusLabel.Caption := 'Installing Naudio MSIX package...';

    // 2. Install / Update MSIX package using PowerShell
    // -ForceApplicationShutdown closes running instances if upgrading
    // -ForceUpdateFromAnyVersion ensures in-place upgrade preserving user data
    PsCommand :=
      '$ErrorActionPreference = ''Stop''; ' +
      '$targetPfn = ''Simpmusic_yhxqqzq5790tp''; ' +
      '$targetPub = ''CN=Naudio''; ' +
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
      '  Get-AppxPackage -Name ''Simpmusic'' | Where-Object { $_.PackageFamilyName -ne $targetPfn } | Remove-AppxPackage -ErrorAction SilentlyContinue; ' +
      '} catch {}; ' +
      '[System.IO.File]::WriteAllText(''' + AumidLog + ''', ($installed.PackageFamilyName + ''!Simpmusic'')); ' +
      'exit 0;';

    ResultCode := -1;
    if not Exec('powershell.exe', '-NoProfile -NonInteractive -ExecutionPolicy Bypass -Command "' + PsCommand + '"', '', SW_HIDE, ewWaitUntilTerminated, ResultCode) or (ResultCode <> 0) then
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
