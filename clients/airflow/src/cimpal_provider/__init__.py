# Copyright (c) 2020-2026 gridDigIt Kft.
# Licensed under the Apache License, Version 2.0.
# SPDX-License-Identifier: Apache-2.0
"""Apache Airflow provider for CimPal: run CimPal commands as jobs on a CimPal server from a DAG.

- :class:`~cimpal_provider.hooks.cimpal.CimPalHook` and the ``cimpal`` connection type;
- :class:`~cimpal_provider.operators.cimpal.CimPalJobOperator` and
  :class:`~cimpal_provider.operators.cimpal.CimPalValidateOperator`;
- :class:`~cimpal_provider.triggers.cimpal.CimPalJobTrigger` (deferrable waiting);
- :class:`~cimpal_provider.sensors.cimpal.CimPalJobSensor`.
"""

from __future__ import annotations

from importlib.metadata import PackageNotFoundError, version

try:
    __version__ = version("airflow-provider-cimpal")
except PackageNotFoundError:  # a source tree that isn't installed
    __version__ = "0+unknown"
