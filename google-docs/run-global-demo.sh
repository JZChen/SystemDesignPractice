#!/usr/bin/env bash
# ==============================================================================
# Google Docs Global Demo Runner
# Launches the Spring Boot service and optionally starts a Cloudflare Tunnel
# to allow friends worldwide to test real-time collaboration with you!
# ==============================================================================

set -e

PORT=${SERVER_PORT:-8080}
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"

echo "=================================================================="
echo "🚀 Starting Google Docs Collaborative Prototype on port $PORT"
echo "=================================================================="

export JAVA_HOME="/opt/homebrew/opt/openjdk@21"
export PATH="/opt/homebrew/opt/openjdk@21/bin:/opt/homebrew/bin:$PATH"

# Run Spring Boot service
cd "$DIR/server"
echo "📦 Building & launching Spring Boot application..."
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=$PORT" &
SERVER_PID=$!

cleanup() {
    echo ""
    echo "🛑 Shutting down server (PID: $SERVER_PID)..."
    kill $SERVER_PID 2>/dev/null || true
    exit 0
}
trap cleanup SIGINT SIGTERM

echo "⏳ Waiting for service to respond on http://127.0.0.1:$PORT..."
while ! curl -s "http://127.0.0.1:$PORT/api/documents" -X POST -H "Content-Type: application/json" -d '{"title":"Health Check"}' > /dev/null; do
    sleep 1
done

echo ""
echo "✅ Server is LIVE at: http://localhost:$PORT/"
echo ""
echo "------------------------------------------------------------------"
echo "🌐 TO COLLABORATE WITH FRIENDS GLOBALLY:"
echo "------------------------------------------------------------------"
echo "Option 1 (Cloudflare Tunnel - Recommended):"
echo "  Run: cloudflared tunnel --url http://localhost:$PORT"
echo "  It will generate a secure HTTPS URL like: https://xxxx.trycloudflare.com"
echo "  Send the link to your friends anywhere in the world to collaborate!"
echo ""
echo "Option 2 (Local testing across multiple browser tabs):"
echo "  Open http://localhost:$PORT in two different windows."
echo "------------------------------------------------------------------"
echo "Press Ctrl+C to stop the server."

wait $SERVER_PID
