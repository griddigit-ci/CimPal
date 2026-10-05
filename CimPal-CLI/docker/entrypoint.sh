#!/bin/sh
# Copyright (c) 2020-2026 gridDigIt Kft.
# Licensed under the EUPL-1.2-or-later.
# SPDX-License-Identifier: EUPL-1.2+
#
# Entrypoint of the CimPal CLI image: runs the CLI with the container's arguments.
#
# Home: a UID that does not own $HOME (any user but cimpal, e.g. `docker run --user` on a Linux
# host or a random UID in Kubernetes) gets a private temporary home for this run, so CimPal's
# ~/.cimpal cache is never shared between users. The JVM is told explicitly (-Duser.home),
# because it would otherwise take the home from the passwd entry, where there is one.
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
    exec java -XX:MaxRAMPercentage=75 -Djava.awt.headless=true "-Duser.home=$HOME" \
        -jar /opt/cimpal/CimPal-CLI.jar "$@"
fi

if [ "$(stat -c %u "$HOME" 2>/dev/null || true)" != "$(id -u)" ]; then
    HOME=$(mktemp -d)
    export HOME
fi

exec 3>&1 1>&2 4<&0 </dev/null
exec /__cacert_entrypoint.sh "$0" --cimpal-exec "$@"
