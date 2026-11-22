@echo off
setlocal
set VERSION=9.6.0
if "%GRADLE_USER_HOME%"=="" set GRADLE_USER_HOME=%USERPROFILE%\.gradle
set INSTALL_DIR=%GRADLE_USER_HOME%\wrapper\dists\lumina-gradle-%VERSION%
set GRADLE_BIN=%INSTALL_DIR%\gradle-%VERSION%\bin\gradle.bat
if not exist "%GRADLE_BIN%" (
  if not exist "%INSTALL_DIR%" mkdir "%INSTALL_DIR%"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-%VERSION%-bin.zip' -OutFile '%INSTALL_DIR%\gradle-%VERSION%-bin.zip'; Expand-Archive -Force '%INSTALL_DIR%\gradle-%VERSION%-bin.zip' '%INSTALL_DIR'; Remove-Item '%INSTALL_DIR%\gradle-%VERSION%-bin.zip'"
  if errorlevel 1 exit /b 1
)
call "%GRADLE_BIN%" %*
endlocal
