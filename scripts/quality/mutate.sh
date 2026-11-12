#!/usr/bin/env bash
# Run PIT, then the score ratchet. QUALITY_MODE is changed or full.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

mode="${QUALITY_MODE:-${1:-full}}"
base="${QUALITY_BASE:-${2:-origin/main}}"
if [[ "$mode" != "changed" && "$mode" != "full" ]]; then
  echo "mode must be changed or full" >&2
  exit 2
fi

java_globs=()
if [[ "$mode" == "changed" ]]; then
  while IFS= read -r file; do
    case "$file" in
      src/main/java/*.java)
        qualified="${file#src/main/java/}"
        qualified="${qualified%.java}"
        java_globs+=("${qualified//\//.}*")
        ;;
    esac
  done < <(git -C "$root" diff --name-only "${base}...HEAD")
fi

run_java=0
if [[ "$mode" == "full" ]]; then
  run_java=1
fi
if [[ ${#java_globs[@]} -gt 0 ]]; then
  run_java=1
fi

if [[ "$run_java" -eq 1 ]]; then
  echo "PIT: $root/pom.xml"
  # A goal-only PIT run does not compile, and then there is nothing to mutate.
  mvn -B -ntp -f "$root/pom.xml" test-compile
  if [[ "$mode" == "changed" ]]; then
    joined="$(IFS=,; echo "${java_globs[*]}")"
    mvn -B -ntp -f "$root/pom.xml" org.pitest:pitest-maven:mutationCoverage \
      -DtargetClasses="$joined"
  else
    mvn -B -ntp -f "$root/pom.xml" org.pitest:pitest-maven:mutationCoverage
  fi
fi

export PYTHONPATH="$root/scripts/quality${PYTHONPATH:+:$PYTHONPATH}"
if [[ "${QUALITY_WRITE:-}" == "1" ]]; then
  exec python3 "$root/scripts/quality/check_mutation.py" --root "$root" --write
fi
exec python3 "$root/scripts/quality/check_mutation.py" --root "$root" --scope "$mode" --base "$base"
