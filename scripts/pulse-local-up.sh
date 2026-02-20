#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
AGENT_JAR="$ROOT_DIR/target/pulse-0.1.0-SNAPSHOT-agent.jar"

if [ ! -f "$AGENT_JAR" ]; then
  echo "building pulse agent jar..."
  (cd "$ROOT_DIR" && ./mvnw -q package)
fi

cat <<'EOF'
pulse local javaagent is ready (no external collector required).

Use this VM option in your Spring Boot app:

-javaagent:/Users/briac/Documents/DEV/pulse/target/pulse-0.1.0-SNAPSHOT-agent.jar=port=17321

Then open:
- http://127.0.0.1:17321
- http://127.0.0.1:17321/api/debug/counters
EOF
