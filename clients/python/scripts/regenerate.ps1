# Copyright (c) 2020-2026 gridDigIt Kft.
# Licensed under the Apache License, Version 2.0.
# SPDX-License-Identifier: Apache-2.0
<#
.SYNOPSIS
    Regenerates src/cimpal_client/_generated from cimpal-v1.json.
.DESCRIPTION
    Pass -Check to only compare (exit 1 when stale). Needs the dev extra:
    pip install -e "clients/python[dev]".
#>
param([switch]$Check)
$ErrorActionPreference = 'Stop'
$script = Join-Path $PSScriptRoot 'regenerate.py'
if ($Check) { & python $script --check } else { & python $script }
exit $LASTEXITCODE
