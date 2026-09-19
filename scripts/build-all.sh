#!/usr/bin/env bash
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mkdir -p "$repo/dist"
find "$repo/dist" -maxdepth 1 -type f -name '*.jar' -delete

for edition in neoforge-1.21.1 client-1.20.1 client-26.1.2; do
  printf '\n=== Building %s ===\n' "$edition"
  bash "$repo/scripts/build.sh" "$edition"
done

printf '\n=== Release artifacts ===\n'
ls -lh "$repo/dist"/*.jar
