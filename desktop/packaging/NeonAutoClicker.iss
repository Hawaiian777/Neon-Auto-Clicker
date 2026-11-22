; Inno Setup 7 (or 6) script for Neon Auto Clicker (Windows). Run by "gradlew :desktop:packageWindows",
; which passes AppVersion, BuildNumber, SourceDir (the jpackage app folder), OutputDir and IconFile.

#ifndef AppVersion
  #define AppVersion "0.0.0"
#endif
#ifndef BuildNumber
  #define BuildNumber "0"
#endif

[Setup]
AppId={{6E3A1F4C-9B57-4C2E-8E1D-3B5C7A9D2F10}
AppName=Neon Auto Clicker
AppVersion={#AppVersion}
AppVerName=Neon Auto Clicker {#AppVersion}
AppPublisher=Neon Auto Clicker
VersionInfoVersion={#AppVersion}
VersionInfoDescription=Neon Auto Clicker installer (build {#BuildNumber})
DefaultDirName={autopf}\Neon Auto Clicker
DefaultGroupName=Neon Auto Clicker
DisableProgramGroupPage=yes
; Installs for the current user without asking for admin rights; "for all users" is offered too.
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
OutputDir={#OutputDir}
OutputBaseFilename=NeonAutoClicker-{#AppVersion}-Setup
SetupIconFile={#IconFile}
UninstallDisplayIcon={app}\Neon Auto Clicker.exe
UninstallDisplayName=Neon Auto Clicker {#AppVersion}
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
; The app keeps running from the tray while the floating controls are open: close it first.
CloseApplications=yes

[Tasks]
Name: "desktopicon"; Description: "Create a &desktop shortcut"; GroupDescription: "Shortcuts:"

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\Neon Auto Clicker"; Filename: "{app}\Neon Auto Clicker.exe"
Name: "{autodesktop}\Neon Auto Clicker"; Filename: "{app}\Neon Auto Clicker.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\Neon Auto Clicker.exe"; Description: "Start Neon Auto Clicker"; Flags: nowait postinstall skipifsilent

; Settings live in %APPDATA%\NeonAutoClicker and are kept on uninstall (like an app update on Android).
