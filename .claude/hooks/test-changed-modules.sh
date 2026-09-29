#!/usr/bin/env bash
# Copyright (c) 2020-2026 gridDigIt Kft.
# Licensed under the EUPL-1.2-or-later.
# SPDX-License-Identifier: EUPL-1.2+
#
# Claude Code Stop hook: at the end of a turn, run the unit tests of the Maven modules whose
# Java files have uncommitted changes. Exit 2 on failure so Claude sees the output and fixes it.
#
# Skips when: this stop was already triggered by the hook (loop guard), no Java files changed,
# or the changed files are identical to the last green run (fingerprint in .claude/state/).

input=$(cat)
if printf '%s' "$input" | grep -Eq '"stop_hook_active"[[:space:]]*:[[:space:]]*true'; then
    exit 0
fi

cd "${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null)}" || exit 0

files=$( { git diff --name-only HEAD -- '*.java'; git ls-files --others --exclude-standard -- '*.java'; } | sort -u)
[ -z "$files" ] && exit 0

modules=$(printf '%s\n' "$files" | cut -d/ -f1 | grep -E '^CimPal-(Core|Main|CLI|CustomWriter)$' | sort -u | paste -sd, -)
[ -z "$modules" ] && exit 0

# Fingerprint = module list + path and content of every changed file (deleted files hash as "deleted").
fingerprint=$( {
    printf '%s\n' "$modules"
    printf '%s\n' "$files" | while IFS= read -r f; do
        if [ -f "$f" ]; then printf '%s %s\n' "$f" "$(git hash-object "$f")"; else printf '%s deleted\n' "$f"; fi
    done
} | git hash-object --stdin)

state_dir=.claude/state
if [ -f "$state_dir/last-green" ] && [ "$(cat "$state_dir/last-green")" = "$fingerprint" ]; then
    exit 0
fi

log=$(mktemp)
if mvn -B -q -pl "$modules" -am test >"$log" 2>&1; then
    mkdir -p "$state_dir"
    printf '%s\n' "$fingerprint" >"$state_dir/last-green"
    rm -f "$log"
    exit 0
fi

{
    echo "Stop hook: 'mvn -B -q -pl $modules -am test' failed. Last 60 lines:"
    tail -n 60 "$log"
} >&2
rm -f "$log"
exit 2
