#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
record_count="${AETHER_RECORDS:-1000000}"
read_count="${AETHER_READS:-250000}"
crash_points="${AETHER_CRASH_POINTS:-4}"
batch_size="${AETHER_BATCH_SIZE:-1000}"
value_bytes="${AETHER_VALUE_BYTES:-256}"
durability="${AETHER_DURABILITY:-group-sync}"
cache_mode="${AETHER_CACHE_MODE:-reopened-warmup}"
database_directory="${AETHER_BENCHMARK_DIR:-$(mktemp -d "${TMPDIR:-/tmp}/aether-cv-benchmark.XXXXXX")}"
result_file="${AETHER_RESULT_FILE:-${database_directory}-results.json}"

echo "Benchmark data will be stored at: ${database_directory}"
echo "Full JSON results will be stored at: ${result_file}"
echo "Use a release JDK, close heavy applications, and avoid quoting results from a thermally throttled run."

if [[ "${cache_mode}" == "cold-open" ]]; then
    echo "Cold-open mode purges the macOS filesystem cache system-wide and may temporarily slow other applications."
    echo "macOS will display an administrator authorization dialog when the purge is ready."
    if [[ "${AETHER_ALLOW_BATTERY:-0}" != "1" ]] && pmset -g batt | grep -q "Battery Power"; then
        echo "Refusing cold-open benchmark on battery power. Connect the charger or set AETHER_ALLOW_BATTERY=1." >&2
        exit 2
    fi
fi

cd "${repository_root}"
exec ./gradlew :modules:aether-benchmarks:run --args="--directory ${database_directory} --output ${result_file} --records ${record_count} --reads ${read_count} --crash-points ${crash_points} --batch-size ${batch_size} --value-bytes ${value_bytes} --durability ${durability} --cache-mode ${cache_mode}"
