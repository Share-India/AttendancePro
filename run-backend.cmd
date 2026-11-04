@echo off
rem Starts the API.
rem
rem Spring Boot does not read .env on its own. Without it the datasource
rem password is empty and Flyway fails on startup with
rem   Access denied for user 'root'@'localhost' (using password: NO)
rem which looks like a MySQL problem and is not.
rem
rem This is a .cmd rather than a .ps1 deliberately: PowerShell's default
rem execution policy on Windows refuses to run .ps1 files, and changing that
rem is a machine-wide security setting nobody should have to touch to start a
rem dev server.
rem
rem   .\run-backend.cmd

setlocal
cd /d "%~dp0"

if not exist ".env" (
    echo No .env found in %~dp0
    echo Copy .env.example to .env and fill in DB_PASSWORD and JWT_SECRET.
    exit /b 1
)

rem eol=# skips comments; "tokens=1,* delims==" keeps any = inside a value,
rem which matters because a base64 JWT secret is often padded with them.
for /f "usebackq eol=# tokens=1,* delims==" %%a in (".env") do (
    if not "%%a"=="" set "%%a=%%b"
)

if "%SERVER_PORT%"=="" set "SERVER_PORT=8080"
echo Loaded .env. Starting the API on port %SERVER_PORT% ...

rem Called by full path on purpose. This machine has
rem NoDefaultCurrentDirectoryInExePath=1, so cmd does not look in the current
rem directory for an executable and a bare "mvnw.cmd" is not found.
cd /d "%~dp0backend"
call "%~dp0backend\mvnw.cmd" spring-boot:run
