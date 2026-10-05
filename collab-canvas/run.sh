#!/usr/bin/env bash
# Collab Canvas launcher.
#   ./run.sh start    build (if needed) and start on 127.0.0.1:8090 in the background
#   ./run.sh stop     stop the background server
#   ./run.sh status   show whether it is running
#   ./run.sh fg       run in the foreground (Ctrl+C to stop)
#   ./run.sh test     Java tests + JS tests
# Env: SERVER_PORT (8090), SERVER_HOST (127.0.0.1), ALLOWED_ORIGINS, JAVA_HOME
set -euo pipefail

cd "$(dirname "$0")"
PORT="${SERVER_PORT:-8090}"
HOST="${SERVER_HOST:-127.0.0.1}"
PID_FILE="target/collab-canvas.pid"
LOG_FILE="target/collab-canvas.log"
JAR="target/collab-canvas-1.0.0-SNAPSHOT.jar"

if [[ -z "${JAVA_HOME:-}" && -d /opt/homebrew/opt/openjdk@21 ]]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21
fi
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"

build() {
  if [[ ! -f "$JAR" ]] || [[ -n "$(find src pom.xml -newer "$JAR" -print -quit)" ]]; then
    echo "Building…"
    mvn -B -q -o -DskipTests package || mvn -B -q -DskipTests package
  fi
}

is_running() {
  [[ -f "$PID_FILE" ]] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null
}

case "${1:-}" in
  start)
    if is_running; then echo "Already running (pid $(cat "$PID_FILE"))"; exit 0; fi
    build
    mkdir -p target
    SERVER_PORT="$PORT" SERVER_HOST="$HOST" nohup "$JAVA" -jar "$JAR" >"$LOG_FILE" 2>&1 &
    echo $! >"$PID_FILE"
    for _ in $(seq 1 60); do
      if curl -fs "http://$HOST:$PORT/api/health" >/dev/null 2>&1; then
        echo "Collab Canvas running: http://$HOST:$PORT/  (pid $(cat "$PID_FILE"), log $LOG_FILE)"
        exit 0
      fi
      if ! is_running; then echo "Server exited; see $LOG_FILE"; tail -20 "$LOG_FILE"; exit 1; fi
      sleep 0.5
    done
    echo "Server did not become healthy in 30s; see $LOG_FILE"; exit 1
    ;;
  stop)
    if is_running; then
      kill "$(cat "$PID_FILE")"
      for _ in $(seq 1 20); do is_running || break; sleep 0.25; done
      is_running && kill -9 "$(cat "$PID_FILE")" || true
      echo "Stopped"
    else
      echo "Not running"
    fi
    rm -f "$PID_FILE"
    ;;
  status)
    if is_running; then echo "Running (pid $(cat "$PID_FILE")) on http://$HOST:$PORT/"; else echo "Not running"; fi
    ;;
  fg)
    build
    SERVER_PORT="$PORT" SERVER_HOST="$HOST" exec "$JAVA" -jar "$JAR"
    ;;
  test)
    mvn -B test
    src/test/js/run-js-tests.sh
    ;;
  *)
    echo "usage: $0 {start|stop|status|fg|test}"; exit 2
    ;;
esac
