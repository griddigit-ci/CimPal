#!/usr/bin/env bash
# Copyright (c) 2020-2026 gridDigIt Kft.
# Licensed under the EUPL-1.2-or-later.
# SPDX-License-Identifier: EUPL-1.2+
#
# Prepares deploy/docker-compose for the client demo (docs/guide/client-demo.md): the synthetic
# sample models, constraints and mapping in ./inputs (the client's files, which it uploads; ./data
# is the server's folder, where serve also keeps its work area), and a random token in
# ./secrets/token. Safe to run again: an existing token is kept.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
compose="$here/../docker-compose"
sample="$here/../../integrations/airflow/examples/data"

mkdir -p "$compose/inputs" "$compose/data" "$compose/secrets"
cp -R "$sample/models" "$sample/constraints" "$sample/mapping.csv" "$compose/inputs/"

if [ ! -s "$compose/secrets/token" ]; then
  py=${PYTHON:-$(command -v python || command -v python3)}
  (umask 077 && "$py" -c "import secrets; print(secrets.token_hex(32))" > "$compose/secrets/token")
  echo "New token written to deploy/docker-compose/secrets/token"
fi

echo "Sample inputs copied to deploy/docker-compose/inputs:"
(cd "$compose/inputs" && find . -type f | sort)
