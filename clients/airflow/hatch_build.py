# Copyright (c) 2020-2026 gridDigIt Kft.
# Licensed under the Apache License, Version 2.0.
# SPDX-License-Identifier: Apache-2.0
"""Build hook: the provider requires cimpal-client at exactly its own version (the CimPal version)."""

from __future__ import annotations

import re
from pathlib import Path
from typing import Any

from hatchling.metadata.plugin.interface import MetadataHookInterface

DEPENDENCIES = ["apache-airflow>=3.2,<4"]


def _version(root: Path) -> str:
    """<cimpal.version> from the repository's pom.xml, or, building from an sdist, from its PKG-INFO."""
    pom = root.parent.parent / "pom.xml"
    if pom.is_file():
        found = re.search(r"<cimpal\.version>([^<]+)</cimpal\.version>", pom.read_text(encoding="utf-8"))
        if found:
            return found.group(1)
    info = root / "PKG-INFO"
    if info.is_file():
        found = re.search(r"^Version: (\S+)$", info.read_text(encoding="utf-8"), re.MULTILINE)
        if found:
            return found.group(1)
    raise RuntimeError("No CimPal version: neither ../../pom.xml nor PKG-INFO found")


class CustomMetadataHook(MetadataHookInterface):
    def update(self, metadata: dict[str, Any]) -> None:
        metadata["dependencies"] = [*DEPENDENCIES, f"cimpal-client=={_version(Path(self.root))}"]
