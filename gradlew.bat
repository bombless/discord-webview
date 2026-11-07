@echo off
setlocal
set VERSION=9.1.0
if not defined GRADLE_USER_HOME set GRADLE_USER_HOME=%USERPROFILE%\.gradle
set BASE=%GRADLE_USER_HOME%\discord-webview-bootstrap
set GRADLE=%BASE%\gradle-%VERSION%\bin\gradle.bat
if not exist "%GRADLE%" (
  if not exist "%BASE%" mkdir "%BASE%"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-%VERSION%-bin.zip' -OutFile '%BASE%\gradle-%VERSION%-bin.zip'; Expand-Archive -Force '%BASE%\gradle-%VERSION%-bin.zip' '%BASE%'; Remove-Item '%BASE%\gradle-%VERSION%-bin.zip'"
)
call "%GRADLE%" %*
