#!/bin/sh
# Generates infra/garage.toml (gitignored) from garage.toml.example, filling
# in freshly-random secrets. Run this once before `docker compose up`.
set -eu

cd "$(dirname "$0")"

if [ -f garage.toml ]; then
  echo "infra/garage.toml already exists, leaving it alone."
  echo "Delete it first if you want to regenerate secrets (this invalidates"
  echo "any existing Garage cluster layout / running node)."
  exit 0
fi

RPC_SECRET=$(openssl rand -hex 32)
ADMIN_TOKEN=$(openssl rand -hex 24)
METRICS_TOKEN=$(openssl rand -hex 24)

sed \
  -e "s#REPLACE_WITH_\$(openssl rand -hex 32)#${RPC_SECRET}#" \
  -e "0,/REPLACE_WITH_\$(openssl rand -hex 24)/s##${ADMIN_TOKEN}#" \
  -e "s#REPLACE_WITH_\$(openssl rand -hex 24)#${METRICS_TOKEN}#" \
  garage.toml.example > garage.toml

echo "Wrote infra/garage.toml with freshly generated secrets."
