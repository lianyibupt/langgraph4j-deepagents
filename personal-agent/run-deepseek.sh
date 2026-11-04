#!/bin/bash
set -euo pipefail
PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# Main reads .env as data. Never source a file containing credentials as shell code.
/bin/bash "$PROJECT_DIR/run.sh" --provider deepseek "$@"
