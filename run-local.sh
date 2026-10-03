#!/usr/bin/env bash
# Builds and runs everything in Docker: Postgres and the application, console included.
# Open http://localhost:8080 and create an organization. Ctrl-C stops it.
set -euo pipefail
cd "$(dirname "$0")"

if ! docker info >/dev/null 2>&1; then
  echo "Docker is not running. Start Docker Desktop and try again." >&2
  exit 1
fi

docker compose up --build
