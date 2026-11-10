#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../.."
command -v uv >/dev/null || { echo 'Install uv first: https://docs.astral.sh/uv/getting-started/installation/'; exit 2; }
[ -x .jobsdb/venv/bin/python ] || uv venv --python 3.12 .jobsdb/venv
uv pip install --python .jobsdb/venv/bin/python -r scripts/jobsdb/requirements.txt
