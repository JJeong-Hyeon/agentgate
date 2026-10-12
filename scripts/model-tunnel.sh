#!/usr/bin/env bash
# Reaches model servers (Ollama, vLLM) on another machine through SSH, without opening their ports to the
# network or changing anything there: each remote port is forwarded to the same port on this machine.
#
#   scripts/model-tunnel.sh start  kjh@192.168.217.249 11434 11435
#   scripts/model-tunnel.sh status kjh@192.168.217.249
#   scripts/model-tunnel.sh stop   kjh@192.168.217.249
#
# Ports default to 11434 (Ollama). Uses SSH key auth (ssh-copy-id first). The model servers keep listening on
# 127.0.0.1 over there; set LLM_BASE_URL / EMBEDDING_BASE_URL to http://host.docker.internal:<port>/v1.
#
# BIND_ADDRESS (default 127.0.0.1) is where the forwarded ports listen here. Docker Desktop (Mac/Windows)
# reaches 127.0.0.1 as host.docker.internal; on a Linux server use the docker bridge, e.g.
# BIND_ADDRESS=172.17.0.1, so containers can connect but the rest of the network cannot.
set -euo pipefail

usage() {
  sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'
  exit 2
}

[[ $# -ge 2 ]] || usage
command=$1
target=$2
shift 2
ports=("$@")
[[ ${#ports[@]} -gt 0 ]] || ports=(11434)
bind=${BIND_ADDRESS:-127.0.0.1}
socket="$HOME/.ssh/agentgate-tunnel-${target//[^A-Za-z0-9._-]/_}.sock"

running() {
  ssh -S "$socket" -O check "$target" >/dev/null 2>&1
}

case $command in
  start)
    if running; then
      echo "Tunnel to $target is already running."
      exit 0
    fi
    forwards=()
    for port in "${ports[@]}"; do
      forwards+=(-L "$bind:$port:127.0.0.1:$port")
    done
    ssh -f -N -M -S "$socket" \
      -o BatchMode=yes -o ExitOnForwardFailure=yes \
      -o ServerAliveInterval=30 -o ServerAliveCountMax=3 \
      "${forwards[@]}" "$target"
    echo "Tunnel to $target started: ${ports[*]} → $bind"
    ;;
  status)
    if running; then
      echo "Tunnel to $target is running."
    else
      echo "Tunnel to $target is not running."
      exit 1
    fi
    ;;
  stop)
    if running; then
      ssh -S "$socket" -O exit "$target" >/dev/null 2>&1
      echo "Tunnel to $target stopped."
    else
      echo "Tunnel to $target is not running."
    fi
    ;;
  *)
    usage
    ;;
esac
