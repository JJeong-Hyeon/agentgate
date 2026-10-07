#!/bin/bash
# Deploys the agent runtime + Ollama to the runtime EC2: syncs the sources, (re)builds the container,
# and pulls the model named in the server's .env. Usage: scripts/deploy-runtime.sh <key.pem>
set -euo pipefail

KEY=${1:?usage: $0 <key.pem>}
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HOST=$(cd "$ROOT/infra" && terraform output -raw runtime_public_ip)
SSH=(ssh -i "$KEY" -o StrictHostKeyChecking=accept-new "ec2-user@$HOST")

"${SSH[@]}" 'mkdir -p ~/agentgate-runtime'
rsync -az --delete -e "ssh -i $KEY" \
  --exclude .venv --exclude tests --exclude '__pycache__' --exclude '*.egg-info' --exclude .env \
  "$ROOT/runtime/" "ec2-user@$HOST:~/agentgate-runtime/runtime/"
rsync -az -e "ssh -i $KEY" "$ROOT/deploy/runtime/compose.yaml" "ec2-user@$HOST:~/agentgate-runtime/"

"${SSH[@]}" bash -s <<'REMOTE'
set -euo pipefail
cd ~/agentgate-runtime
if [ ! -f .env ]; then
  echo "~/agentgate-runtime/.env is missing; create it from deploy/runtime/.env.example first." >&2
  exit 1
fi
docker compose up -d --build
model=$(grep -E '^LLM_MODEL=' .env | cut -d= -f2-)
docker compose exec -T ollama ollama pull "${model:-qwen2.5:7b}"
curl -sf --retry 10 --retry-connrefused --retry-delay 2 http://localhost:8000/health
echo
REMOTE
echo "Runtime deployed on $HOST"
