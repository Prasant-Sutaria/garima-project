#!/usr/bin/env bash
# Starts PostgreSQL if it is not running, then the Spring Boot backend, then the registration frontend.
# Database: Docker Compose (backend/compose.yaml) when Docker is available, otherwise the native PostgreSQL service.
# Local secrets are kept in backend/.env (git-ignored) and created on the first run.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND="$ROOT/backend"
FRONTEND="$ROOT/registration-frontend"
ENV_FILE="$BACKEND/.env"
HEALTH_URL="http://127.0.0.1:8080/api/v1/health"
LOG_FILE="$BACKEND/backend.log"
PID_FILE="$BACKEND/backend.pid"
TIMEOUT="${STARTUP_TIMEOUT_SECONDS:-180}"

die() { echo "ERROR: $*" >&2; exit 1; }
need() { command -v "$1" >/dev/null 2>&1 || die "$1 was not found on PATH."; }
secret() { if command -v openssl >/dev/null 2>&1; then openssl rand -hex 24; else head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n'; fi; }
docker_ok() { command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; }
db_port_open() { (exec 3<>/dev/tcp/127.0.0.1/5432) 2>/dev/null; }
api_healthy() { curl -fs -o /dev/null "$HEALTH_URL"; }

need mvn
need node
need npm
need curl

if docker_ok; then DB_MODE=docker; else DB_MODE=native; fi

if [ ! -f "$ENV_FILE" ]; then
  echo "Creating backend/.env with local secrets."
  db_password=""
  if [ "$DB_MODE" = native ]; then
    read -r -s -p "Password of PostgreSQL user usage_api on database usage: " db_password
    echo
  fi
  [ -n "$db_password" ] || db_password="$(secret)"
  umask 077
  {
    echo "ADMIN_USERNAME=study-admin"
    echo "ADMIN_PASSWORD=$(secret)"
    echo "LOCAL_KEYSTORE_PASSWORD=$(secret)"
    echo "DB_PASSWORD=$db_password"
    echo "DB_URL=jdbc:postgresql://localhost:5432/usage"
    echo "DB_USERNAME=usage_api"
  } > "$ENV_FILE"
fi

# Parse KEY=VALUE lines literally (no shell evaluation of the values).
while IFS='=' read -r key value || [ -n "$key" ]; do
  value="${value%$'\r'}"
  case "$key" in ''|\#*) continue ;; esac
  export "$key=$value"
done < "$ENV_FILE"

if [ "$DB_MODE" = docker ]; then
  echo "[1/3] Database: Docker Compose PostgreSQL"
  if (cd "$BACKEND" && docker compose ps --status running --services 2>/dev/null | grep -qx postgres); then
    echo "PostgreSQL container is already running."
  else
    echo "Starting the PostgreSQL container..."
    (cd "$BACKEND" && docker compose up -d --wait postgres) || die "the PostgreSQL container did not become healthy."
  fi
else
  echo "[1/3] Database: native PostgreSQL service"
  if db_port_open; then
    echo "PostgreSQL is already running."
  else
    echo "Starting PostgreSQL..."
    case "$(uname -s)" in
      MINGW*|MSYS*|CYGWIN*)
        powershell.exe -NoProfile -Command "\$s=Get-Service 'postgresql*' -ErrorAction SilentlyContinue | Select-Object -First 1; if(-not \$s){exit 2}; if(\$s.Status -ne 'Running'){Start-Service \$s.Name -ErrorAction Stop}" \
          || die "could not start the PostgreSQL service; run this script as Administrator." ;;
      Darwin)
        service="$(brew services list 2>/dev/null | awk '/^postgresql/ {print $1; exit}')"
        [ -n "$service" ] || die "no Homebrew PostgreSQL service found."
        brew services start "$service" ;;
      *)
        if command -v systemctl >/dev/null 2>&1; then
          sudo systemctl start postgresql || die "could not start the postgresql service."
        else
          die "no way to start PostgreSQL found; start it manually or install Docker."
        fi ;;
    esac
  fi
fi

for _ in $(seq 1 30); do db_port_open && break; sleep 2; done
db_port_open || die "PostgreSQL is not accepting connections on 127.0.0.1:5432."

export SPRING_PROFILES_ACTIVE=local
export SERVER_ADDRESS=127.0.0.1

if api_healthy; then
  echo "[2/3] Backend is already running."
else
  echo "[2/3] Starting the Spring Boot backend (log: backend/backend.log)..."
  (cd "$BACKEND"; nohup mvn spring-boot:run -Dspring-boot.run.fork=false > "$LOG_FILE" 2>&1 & echo $! > "$PID_FILE")
  ready=""
  for _ in $(seq 1 $((TIMEOUT / 2))); do
    if api_healthy; then ready=1; break; fi
    sleep 2
  done
  [ -n "$ready" ] || die "the backend did not become healthy in ${TIMEOUT}s. See $LOG_FILE."
  echo "Backend is healthy at http://127.0.0.1:8080"
fi

echo "[3/3] Starting the registration frontend at http://127.0.0.1:3000 (Ctrl+C to stop)"
echo "The backend keeps running in the background; stop it with: kill \$(cat \"$PID_FILE\")"
export BACKEND_URL=http://127.0.0.1:8080
cd "$FRONTEND"
exec npm start
