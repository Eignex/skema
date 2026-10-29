#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
report_dir="${1:-$project_dir/build/test-results/jvmTest}"
if [[ "$report_dir" != /* ]]; then
    report_dir="$PWD/$report_dir"
fi

exec "$project_dir/gradlew" -p "$project_dir" validateJsonSchemas "-PschemaReportDir=$report_dir"
