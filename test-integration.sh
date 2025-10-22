#!/usr/bin/env bash

set -e

echo "==> Starting replikativ integration tests..."

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Create log file
SERVER_LOG=$(mktemp)

# Start the server in background, logging to file
echo -e "${YELLOW}==> Starting JVM integration test server...${NC}"
clojure -M:dev -m replikativ.integration-server > "$SERVER_LOG" 2>&1 &
SERVER_PID=$!

# Function to cleanup on exit
cleanup() {
    echo -e "${YELLOW}==> Cleaning up...${NC}"
    if [ ! -z "$SERVER_PID" ]; then
        # Kill the process group to ensure child Java processes are terminated
        kill -TERM -$SERVER_PID 2>/dev/null || true
        sleep 1
        # Force kill if still running
        kill -KILL -$SERVER_PID 2>/dev/null || true
        # Don't wait - it can hang. Just move on.
    fi
    # Also kill any Java processes listening on our port as a fallback
    lsof -ti:47297 | xargs kill -9 2>/dev/null || true
    rm -f "$SERVER_LOG"
}

# Register cleanup on exit
trap cleanup EXIT INT TERM

# Wait for server to start (check if port is open)
echo -e "${YELLOW}==> Waiting for server to start...${NC}"
MAX_WAIT=20
WAITED=0
while ! nc -z localhost 47297 2>/dev/null; do
    if [ $WAITED -ge $MAX_WAIT ]; then
        echo -e "${RED}==> Server failed to start within ${MAX_WAIT} seconds${NC}"
        cat "$SERVER_LOG"
        exit 1
    fi
    sleep 1
    WAITED=$((WAITED + 1))
done

# Port is open, now wait for server to be fully initialized
WAITED=0
while ! grep -q "Server started successfully" "$SERVER_LOG" 2>/dev/null; do
    if [ $WAITED -ge $MAX_WAIT ]; then
        echo -e "${RED}==> Server failed to fully initialize within ${MAX_WAIT} seconds${NC}"
        cat "$SERVER_LOG"
        exit 1
    fi
    sleep 1
    WAITED=$((WAITED + 1))
done

# Show server output
cat "$SERVER_LOG"
echo -e "${GREEN}==> Server started successfully${NC}"

# Compile the integration test
echo -e "${YELLOW}==> Compiling integration tests...${NC}"
clojure -M:shadow-cljs compile integration

# Run the integration test
echo -e "${YELLOW}==> Running integration tests...${NC}"
if node target/integration-test.js; then
    echo -e "${GREEN}==> Integration tests passed!${NC}"
    exit 0
else
    echo -e "${RED}==> Integration tests failed!${NC}"
    exit 1
fi
