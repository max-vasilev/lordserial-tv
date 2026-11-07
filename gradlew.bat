@echo off
set APP_HOME=%~dp0
set DIST=%USERPROFILE%\.gradle\wrapper\dists\gradle-8.13-bin\local
set GRADLE=%DIST%\gradle-8.13\bin\gradle.bat
if not exist "%GRADLE%" (
  if not exist "%DIST%" mkdir "%DIST%"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest 'https://services.gradle.org/distributions/gradle-8.13-bin.zip' -OutFile '%DIST%\gradle-8.13-bin.zip'; Expand-Archive -Force '%DIST%\gradle-8.13-bin.zip' '%DIST%'"
)
call "%GRADLE%" -p "%APP_HOME%" %*
