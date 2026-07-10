#!/usr/bin/env bash
# Pull the atm-microstructure tape (Parquet archive + latest daily CSV) from the prod EC2 box into ./data.
# Packages on the box first (tar+gzip) so the transfer is small. Safe to re-run; overwrites local copies.
set -euo pipefail

PEM="${ALGO_PEM:-/c/aws/algo-trade.pem}"
HOST="${ALGO_HOST:-ubuntu@13.200.172.216}"        # Elastic IP — stable across stop/start
REMOTE_TUNING="/home/ubuntu/adv-algo-trade/data/tuning"
DATA_DIR="$(cd "$(dirname "$0")/../data" && pwd)"

echo "[fetch] packaging on EC2..."
ssh -i "$PEM" -o StrictHostKeyChecking=no "$HOST" "
  cd '$REMOTE_TUNING'
  tar czf /tmp/micro-arc.tgz microstructure-archive
  latest=\$(ls -t atm-microstructure-*.csv | head -1)
  gzip -c \"\$latest\" > /tmp/micro-latest.csv.gz
  echo \"\$latest\" > /tmp/micro-latest.name
"

echo "[fetch] downloading to $DATA_DIR ..."
scp -i "$PEM" -o StrictHostKeyChecking=no \
    "$HOST:/tmp/micro-arc.tgz" "$HOST:/tmp/micro-latest.csv.gz" "$HOST:/tmp/micro-latest.name" \
    "$DATA_DIR/"

echo "[fetch] extracting..."
cd "$DATA_DIR"
tar xzf micro-arc.tgz
name=$(cat micro-latest.name)
gunzip -kf micro-latest.csv.gz
mv -f micro-latest.csv "$name"
echo "[fetch] done: $(du -sh microstructure-archive | cut -f1) archive + $name"
