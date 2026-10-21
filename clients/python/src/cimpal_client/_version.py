# Copyright (c) 2020-2026 gridDigIt Kft.
# Licensed under the Apache License, Version 2.0.
# SPDX-License-Identifier: Apache-2.0
"""The package version, which is the CimPal version it was built with."""

from __future__ import annotations

from importlib.metadata import PackageNotFoundError, version

try:
    __version__ = version("cimpal-client")
except PackageNotFoundError:  # running from a source tree that isn't installed
    __version__ = "0+unknown"
