@echo off
rem ==========================================================================
rem  Builds Neon Auto Clicker for Windows: just double-click this file.
rem  Result (opens when done), in desktop\build\dist :
rem    NeonAutoClicker-<version>-Setup.exe            installer (needs Inno Setup 7 or 6)
rem    NeonAutoClicker-<version>-Windows-portable.zip  no install needed
rem  The first build downloads what it needs, so it takes a few minutes.
rem ==========================================================================
setlocal
cd /d "%~dp0"
title Building Neon Auto Clicker for Windows

rem Java: use Android Studio's built-in Java if there is one, otherwise JAVA_HOME.
if exist "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" (
    set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
)
if not defined JAVA_HOME (
    where java >nul 2>nul
    if errorlevel 1 (
        echo.
        echo  Java was not found. Install Android Studio, or Java 21 from https://adoptium.net
        echo  then run this file again.
        echo.
        pause
        exit /b 1
    )
)

set "INNO="
for %%D in ("%ProgramFiles%" "%ProgramFiles(x86)%" "%LOCALAPPDATA%\Programs") do (
    if exist "%%~D\Inno Setup 7\ISCC.exe" set "INNO=1"
    if exist "%%~D\Inno Setup 6\ISCC.exe" set "INNO=1"
)
if not defined INNO (
    echo.
    echo  Note: Inno Setup is not installed, so only the portable zip will be made.
    echo  For the installer .exe, install Inno Setup 7 free from https://jrsoftware.org/isdl.php
    echo  and run this file again.
    echo.
)

call gradlew.bat :desktop:packageWindows -Pneon.desktopOnly=true
if errorlevel 1 (
    echo.
    echo  The build failed. Scroll up for the first error message.
    echo.
    pause
    exit /b 1
)

echo.
echo  Done. Opening the folder with the installer / portable app...
start "" "%~dp0desktop\build\dist"
echo.
pause
