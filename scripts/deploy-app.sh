#!/bin/bash
# Builds AgentGate with the frontend bundled in and deploys the jar to the app EC2.
# Usage: scripts/deploy-app.sh <key.pem>
set -euo pipefail

KEY=${1:?usage: $0 <key.pem>}
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HOST=$(cd "$ROOT/infra" && terraform output -raw ec2_public_ip)
SSH=(ssh -i "$KEY" -o StrictHostKeyChecking=accept-new "ec2-user@$HOST")

(cd "$ROOT" && ./gradlew -q bootJar -PwithFrontend)
JAR=$(find "$ROOT/build/libs" -name '*.jar' ! -name '*-plain.jar' | head -1)

"${SSH[@]}" 'mkdir -p ~/agentgate'
scp -i "$KEY" "$JAR" "ec2-user@$HOST:~/agentgate/agentgate.jar"
scp -i "$KEY" "$ROOT/infra/systemd/agentgate.service" "ec2-user@$HOST:/tmp/agentgate.service"

"${SSH[@]}" bash -s <<'REMOTE'
set -euo pipefail
if [ ! -f ~/agentgate/.env ]; then
  echo "~/agentgate/.env is missing; see docs/DEPLOYMENT.md." >&2
  exit 1
fi
docker start agentgate-redis >/dev/null 2>&1 \
  || docker run -d --name agentgate-redis -p 6379:6379 --restart unless-stopped redis:latest >/dev/null
sudo mv /tmp/agentgate.service /etc/systemd/system/agentgate.service
sudo systemctl daemon-reload
sudo systemctl enable agentgate >/dev/null
sudo systemctl restart agentgate
curl -sf --retry 30 --retry-connrefused --retry-delay 3 http://localhost:8080/actuator/health
echo
REMOTE
echo "AgentGate deployed on $HOST"
