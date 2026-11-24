@echo off
rem Uploads this folder to GitHub: the code and README as a public repository, and release\Gauntlet-Odin3.apk as a
rem GitHub Release download. No GitHub CLI needed - only Git for Windows (git-scm.com), whose sign-in window opens the
rem first time. Run "upload.bat check" to only run the checks.
if /i "%~1"=="check" (
  powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0upload.ps1" -DryRun
) else (
  powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0upload.ps1"
)
echo.
pause
