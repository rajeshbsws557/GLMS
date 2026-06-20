@echo off
echo =========================================
echo    Bakir Khata API - Windows Runner
echo =========================================
echo.
echo [1/2] Building the project (using Maven Wrapper)...
call mvnw.cmd clean package -DskipTests
if %ERRORLEVEL% neq 0 (
    echo.
    echo [ERROR] Build failed!
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo [2/2] Starting the Backend Server...
echo.
java -jar target\bakir-khata-api-1.0.0.jar
pause
