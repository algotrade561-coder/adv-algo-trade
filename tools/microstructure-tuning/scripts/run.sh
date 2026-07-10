#!/usr/bin/env bash
# Run the microstructure threshold analysis over ./data. Resolves the DuckDB JDBC jar from ~/.m2
# (falls back to scripts/lib/). Java 17+ (uses the single-file source launcher).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
DATA="$(cd "$HERE/../data" && pwd)"
DUCK_VER="${DUCK_VER:-1.1.3}"
M2_JAR="$HOME/.m2/repository/org/duckdb/duckdb_jdbc/$DUCK_VER/duckdb_jdbc-$DUCK_VER.jar"
LIB_JAR="$HERE/lib/duckdb_jdbc-$DUCK_VER.jar"

if   [ -f "$M2_JAR" ]; then JAR="$M2_JAR"
elif [ -f "$LIB_JAR" ]; then JAR="$LIB_JAR"
else echo "DuckDB jar not found ($M2_JAR or $LIB_JAR). Build the project once (mvn) or drop the jar in scripts/lib/."; exit 1
fi

ARCHIVE="$DATA/microstructure-archive"
CSV="$(ls -t "$DATA"/atm-microstructure-*.csv 2>/dev/null | head -1)"
[ -d "$ARCHIVE" ] || { echo "No data — run scripts/fetch-data.sh first."; exit 1; }

echo "[run] jar=$JAR"
echo "[run] archive=$ARCHIVE"
echo "[run] csv=$CSV"
java -cp "$JAR" "$HERE/MicroTune.java" "$ARCHIVE" "$CSV"
