#!/usr/bin/env bash
# Copyright (c) 2020-2026 gridDigIt Kft.
# Licensed under the Apache License, Version 2.0.
# SPDX-License-Identifier: Apache-2.0
#
# Regenerates src/cimpal_client/_generated from cimpal-v1.json (pass --check to only compare).
# Needs the dev extra: pip install -e "clients/python[dev]".
set -euo pipefail
exec python "$(dirname "$0")/regenerate.py" "$@"
