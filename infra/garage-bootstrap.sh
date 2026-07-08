#!/bin/sh
# Idempotent single-node Garage bootstrap for local/dev use:
#  - assigns this node its storage layout (once)
#  - creates the PDF bucket
#  - creates an S3 access key and grants it read/write on the bucket
#  - writes the resulting credentials to /shared/credentials.env
#
# Idempotency is gated on the presence of /shared/credentials.env (our own
# marker), rather than by parsing Garage's human-readable CLI output, since
# that output's exact formatting can vary between versions.
set -eu

GARAGE="/garage -c /etc/garage.toml"
BUCKET="${GARAGE_BUCKET:-kindle-pdfs}"
KEY_NAME="${GARAGE_KEY_NAME:-kindle-app-key}"
OUT="/shared/credentials.env"

echo "Waiting for garage admin API..."
until $GARAGE status >/dev/null 2>&1; do
  sleep 1
done

if [ -f "$OUT" ]; then
  echo "Already bootstrapped (found $OUT), skipping. Delete it (and the"
  echo "garage-shared volume if you want a clean slate) to re-run."
  cat "$OUT"
  exit 0
fi

echo "Determining local node ID..."
NODE_ID=$($GARAGE node id -q | cut -d'@' -f1)
if [ -z "$NODE_ID" ]; then
  echo "Could not determine node ID via 'garage node id -q'" >&2
  exit 1
fi
echo "Node ID: $NODE_ID"

echo "Assigning single-node layout (safe to ignore 'already has a role' errors on retry)..."
$GARAGE layout assign -z dc1 -c 5G "$NODE_ID" || true
$GARAGE layout apply --version 1 || true

echo "Creating bucket $BUCKET (safe to ignore 'already exists' errors on retry)..."
$GARAGE bucket create "$BUCKET" || true

echo "Creating access key $KEY_NAME..."
$GARAGE key create "$KEY_NAME" > /tmp/key.txt
cat /tmp/key.txt
KEY_ID=$(awk -F': ' '/Key ID/{print $2}' /tmp/key.txt | tr -d ' \t')
SECRET=$(awk -F': ' '/Secret key/{print $2}' /tmp/key.txt | tr -d ' \t')

if [ -z "$KEY_ID" ] || [ -z "$SECRET" ]; then
  echo "Could not parse 'garage key create' output — see /tmp/key.txt above." >&2
  echo "Bootstrap the key manually (see infra/README.md) and write" >&2
  echo "GARAGE_ACCESS_KEY_ID / GARAGE_SECRET_ACCESS_KEY into web/.env yourself." >&2
  exit 1
fi

$GARAGE bucket allow --read --write --owner "$BUCKET" --key "$KEY_NAME"

{
  echo "GARAGE_ACCESS_KEY_ID=$KEY_ID"
  echo "GARAGE_SECRET_ACCESS_KEY=$SECRET"
} > "$OUT"

echo "Garage bootstrap complete. Copy these into web/.env:"
cat "$OUT"
