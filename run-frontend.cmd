@echo off
rem Starts the Vite dev server on port 5175.
rem
rem Runs from anywhere: the path is resolved from this script's own location,
rem so it cannot be broken by standing in the wrong directory.
rem
rem npm.cmd rather than npm on purpose. PowerShell's default execution policy
rem on Windows refuses to run npm.ps1, and loosening a machine-wide security
rem setting is not a reasonable price for starting a dev server.
rem
rem   .\run-frontend.cmd

setlocal
cd /d "%~dp0frontend" || exit /b 1
echo Starting the web app on port 5175 ...
call npm.cmd run dev
