#!/usr/bin/env bash
# Fails if any action reference under the given directory (default .github/workflows) is not
# pinned: every `uses:` value must be a local action (./…), owner/repo[/path]@<40-hex commit SHA>
# or docker://…@sha256:<64-hex digest>. Tags, branches and short SHAs fail.
#
# The values are extracted wherever they sit on a line (block or flow style, quoted key or
# value, several per line); comments are stripped first. A value on the line after its key
# extracts as empty and fails, so the check errs on the side of failing.
set -euo pipefail
dir="${1:-.github/workflows}"

# shellcheck disable=SC2016 # the awk program is single-quoted on purpose
refs=$(find "$dir" -type f \( -name '*.yml' -o -name '*.yaml' \) -print0 | xargs -0 awk '
  {
    line = $0
    sub(/^[[:space:]]*#.*/, "", line)
    sub(/[[:space:]]#.*$/, "", line)
    while (match(line, /(^|[[:space:]{,])["\047]?uses["\047]?:/)) {
      line = substr(line, RSTART + RLENGTH)
      sub(/^[[:space:]]*/, "", line)
      match(line, /^[^[:space:],}]*/)
      ref = substr(line, 1, RLENGTH)
      gsub(/["\047]/, "", ref)
      print FILENAME ":" FNR ": " ref
    }
  }')
if [ -z "$refs" ]; then
  echo "no action references found under $dir"
  exit 0
fi

bad=$(printf '%s\n' "$refs" | grep -vE ': (\./[^ ]*|[^@ ]+@[0-9a-f]{40}|docker://[^@ ]+@sha256:[0-9a-f]{64})$' || true)
if [ -n "$bad" ]; then
  printf '%s\n' "$bad"
  echo "::error::action reference not pinned by full commit SHA (or docker:// by sha256 digest)"
  exit 1
fi
echo "$(printf '%s\n' "$refs" | wc -l | tr -d ' ') action references, all pinned"
