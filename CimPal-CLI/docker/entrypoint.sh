#!/bin/sh
# Copyright (c) 2020-2026 gridDigIt Kft.
# Licensed under the EUPL-1.2-or-later.
# SPDX-License-Identifier: EUPL-1.2+
#
# Entrypoint of the CimPal CLI image: runs the CLI with the container's arguments.
#
# Home: a UID that does not own $HOME (any user but cimpal, e.g. `docker run --user` on a Linux
# host or a random UID in Kubernetes), or that can't write it (a read-only root filesystem), gets
# a private temporary home in /tmp for this run, so CimPal's ~/.cimpal cache is never shared
# between users. The JVM is told explicitly (-Duser.home), because it would otherwise take the
# home from the passwd entry, where there is one.
#
# JVM options: container defaults (heap at 75 % of the memory limit, exit 3 on out-of-memory),
# then $JAVA_OPTS, whose later flags win, then the options this script relies on. VM output (the
# out-of-memory message, unified-logging warnings) goes to stderr, so stdout stays clean for JSON
# and MCP.
#
# Certificates: the base image's /__cacert_entrypoint.sh imports extra CA certificates when
# USE_SYSTEM_CA_CERTS is set (certificates mounted at /certificates/*.crt). That is how a
# corporate TLS-inspection root reaches the JVM for remote owl:imports. The hook reports its
# progress on stdout, but stdout carries the JSON of `--format json` and the MCP protocol, so the
# hook runs with stdout pointed at stderr and stdin at /dev/null, and the JVM gets both back.
set -eu

if [ "${1-}" = "--cimpal-exec" ]; then
    shift
    exec 1>&3 3>&- 0<&4 4<&-
    # JAVA_OPTS is split on blanks like in other Java images; no globbing of its words.
    set -f
    # shellcheck disable=SC2086
    # Unified logging writes its warnings to stdout by default; send them to stderr. JAVA_OPTS can
    # add -Xlog outputs of its own (they must name stderr or a file, see docs/cli/docker.md).
    exec java -XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError \
        -Xlog:disable -Xlog:all=warning:stderr:uptime,level,tags ${JAVA_OPTS-} \
        -XX:+DisplayVMOutputToStderr -Djava.awt.headless=true "-Duser.home=$HOME" \
        -jar /opt/cimpal/CimPal-CLI.jar "$@"
fi

# CimPal prefers LOCALAPPDATA over the home for its cache; on Linux that would bypass the private home.
unset LOCALAPPDATA

if [ "$(stat -c %u "$HOME" 2>/dev/null || true)" != "$(id -u)" ] || [ ! -w "$HOME" ]; then
    if ! HOME=$(mktemp -d 2>/dev/null); then
        echo "[ERROR] CimPal needs a writable /tmp. With a read-only root filesystem, add --tmpfs /tmp" \
            "(Kubernetes: an emptyDir volume at /tmp)." >&2
        exit 2
    fi
    export HOME
fi

exec 3>&1 1>&2 4<&0 </dev/null
exec /__cacert_entrypoint.sh "$0" --cimpal-exec "$@"
