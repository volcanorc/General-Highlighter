@echo off
setlocal

set "GRADLE_VERSION=8.11.1"
set "ROOT_DIR=%~dp0"
set "GRADLE_HOME=%ROOT_DIR%.gradle-launcher\gradle-%GRADLE_VERSION%"
set "GRADLE_BIN=%GRADLE_HOME%\bin\gradle.bat"
set "GRADLE_ZIP=%ROOT_DIR%.gradle-launcher\gradle-%GRADLE_VERSION%-bin.zip"

if not exist "%GRADLE_BIN%" (
  powershell -NoProfile -ExecutionPolicy Bypass -Command "New-Item -ItemType Directory -Force -Path '%ROOT_DIR%.gradle-launcher' | Out-Null; if (-not (Test-Path '%GRADLE_ZIP%')) { Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile '%GRADLE_ZIP%' }; Expand-Archive -Path '%GRADLE_ZIP%' -DestinationPath '%ROOT_DIR%.gradle-launcher' -Force"
)

if not exist "%GRADLE_BIN%" (
  echo Gradle %GRADLE_VERSION% could not be prepared.
  exit /b 1
)

call "%GRADLE_BIN%" %*
