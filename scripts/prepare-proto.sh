#!/usr/bin/env bash
# Re-import api.proto from a kubeedge / k8s.io/cri-api source tree and strip
# gogoproto extensions so vanilla protoc + protoc-gen-grpc-kotlin can consume it.
#
# Usage:
#   scripts/prepare-proto.sh /path/to/kubeedge

set -euo pipefail

SRC=${1:-/home/all/bao/kubeedge}
IN="$SRC/vendor/k8s.io/cri-api/pkg/apis/runtime/v1/api.proto"
OUT="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/proto/api.proto"

if [[ ! -f "$IN" ]]; then
  echo "source proto not found: $IN" >&2
  exit 1
fi

mkdir -p "$(dirname "$OUT")"

sed -E '/gogo\.proto/d; /option \(gogoproto/d' "$IN" > "$OUT"

# Inject Java options once
if ! grep -q 'java_package' "$OUT"; then
  sed -i 's|option go_package = "k8s.io/cri-api/pkg/apis/runtime/v1";|option go_package = "k8s.io/cri-api/pkg/apis/runtime/v1";\noption java_package = "io.runtime.v1";\noption java_multiple_files = true;\noption java_outer_classname = "CriProto";|' "$OUT"
fi

echo "wrote $OUT ($(wc -l < "$OUT") lines)"
