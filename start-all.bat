@echo off
setlocal EnableExtensions
rem Starts PostgreSQL if it is not running, then the Spring Boot backend, then the registration frontend.
rem Database: Docker Compose (backend\compose.yaml) when Docker is available, otherwise the native PostgreSQL service.
rem Local secrets are kept in backend\.env (git-ignored) and created on the first run.

set "ROOT=%~dp0"
set "BACKEND=%ROOT%backend"
set "FRONTEND=%ROOT%registration-frontend"
set "ENV_FILE=%BACKEND%\.env"
set "HEALTH_URL=http://127.0.0.1:8080/api/v1/health"
if not defined STARTUP_TIMEOUT_SECONDS set "STARTUP_TIMEOUT_SECONDS=180"
set /a WAIT_LOOPS=%STARTUP_TIMEOUT_SECONDS%/2

where mvn >nul 2>&1
if errorlevel 1 (
  echo ERROR: Maven was not found on PATH.
  exit /b 1
)
where node >nul 2>&1
if errorlevel 1 (
  echo ERROR: Node.js was not found on PATH.
  exit /b 1
)
where curl.exe >nul 2>&1
if errorlevel 1 (
  echo ERROR: curl.exe was not found on PATH.
  exit /b 1
)

set "DB_MODE=native"
docker info >nul 2>&1
if not errorlevel 1 set "DB_MODE=docker"

if not exist "%ENV_FILE%" (
  echo Creating backend\.env with local secrets.
  set "DB_PW_IN="
  if "%DB_MODE%"=="native" set /p "DB_PW_IN=Password of PostgreSQL user usage_api on database usage: "
  powershell -NoProfile -Command "$g={[guid]::NewGuid().ToString('N')+[guid]::NewGuid().ToString('N')}; $db=$env:DB_PW_IN; if(-not $db){$db=& $g}; Set-Content -Path $env:ENV_FILE -Encoding ascii -Value @('ADMIN_USERNAME=study-admin',('ADMIN_PASSWORD='+(& $g)),('LOCAL_KEYSTORE_PASSWORD='+(& $g)),('DB_PASSWORD='+$db),'DB_URL=jdbc:postgresql://localhost:5432/usage','DB_USERNAME=usage_api')"
  if errorlevel 1 (
    echo ERROR: could not create backend\.env.
    exit /b 1
  )
)

for /f "usebackq eol=# tokens=1* delims==" %%A in ("%ENV_FILE%") do set "%%A=%%B"

if "%DB_MODE%"=="docker" (
  echo [1/3] Database: Docker Compose PostgreSQL
  pushd "%BACKEND%"
  docker compose ps --status running --services 2>nul | findstr /x /c:"postgres" >nul
  if errorlevel 1 (
    echo Starting the PostgreSQL container...
    docker compose up -d --wait postgres
    if errorlevel 1 (
      popd
      echo ERROR: the PostgreSQL container did not become healthy.
      exit /b 1
    )
  ) else (
    echo PostgreSQL container is already running.
  )
  popd
) else (
  echo [1/3] Database: native PostgreSQL service
  powershell -NoProfile -Command "$s=Get-Service 'postgresql*' -ErrorAction SilentlyContinue | Select-Object -First 1; if(-not $s){exit 2}; if($s.Status -ne 'Running'){Write-Host ('Starting service '+$s.Name+'...'); Start-Service $s.Name -ErrorAction Stop} else {Write-Host ('Service '+$s.Name+' is already running.')}"
  if errorlevel 1 (
    echo ERROR: no PostgreSQL service was found or it could not be started. Install PostgreSQL or Docker, or run this script as Administrator.
    exit /b 1
  )
)

set "DB_READY="
for /l %%i in (1,1,30) do if not defined DB_READY (
  powershell -NoProfile -Command "try{$c=New-Object Net.Sockets.TcpClient('127.0.0.1',5432);$c.Close();exit 0}catch{exit 1}"
  if errorlevel 1 (ping -n 3 127.0.0.1 >nul) else (set "DB_READY=1")
)
if not defined DB_READY (
  echo ERROR: PostgreSQL is not accepting connections on 127.0.0.1:5432.
  exit /b 1
)

set "SPRING_PROFILES_ACTIVE=local"
set "SERVER_ADDRESS=127.0.0.1"
curl.exe -fs -o nul "%HEALTH_URL%"
if not errorlevel 1 (
  echo [2/3] Backend is already running.
) else (
  echo [2/3] Starting the Spring Boot backend in a new window...
  start "Usage API backend" /D "%BACKEND%" cmd /k mvn spring-boot:run -Dspring-boot.run.fork=false
  set "API_READY="
  for /l %%i in (1,1,%WAIT_LOOPS%) do if not defined API_READY (
    curl.exe -fs -o nul "%HEALTH_URL%"
    if errorlevel 1 (ping -n 3 127.0.0.1 >nul) else (set "API_READY=1")
  )
  if not defined API_READY (
    echo ERROR: the backend did not become healthy in %STARTUP_TIMEOUT_SECONDS% seconds. Check the backend window.
    exit /b 1
  )
  echo Backend is healthy at http://127.0.0.1:8080
)

echo [3/3] Starting the registration frontend at http://127.0.0.1:3000 ^(Ctrl+C to stop^)
echo The backend keeps running in its own window; close that window to stop it.
set "BACKEND_URL=http://127.0.0.1:8080"
cd /d "%FRONTEND%"
call npm start
