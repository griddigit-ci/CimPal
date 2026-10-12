<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->
# Testing track

WP prefix `TEST`. Status of each WP is kept only in the master table in [`../README.md`](../README.md). Test layers (L1 unit … L11 mutation) and methodology: [CimPal — Security & Testing Plan](https://claude.ai/code/artifact/19c44ed1-1c4d-48ab-be25-072492b6ffd5). The feature × layer matrix lives in [TEST-3](TEST-3.md).

| ID | Work package | Goal |
| --- | --- | --- |
| [TEST-1](TEST-1.md) | Test harness | Shared, network-free, snapshot-based test infrastructure |
| [TEST-2](TEST-2.md) | Regression tests for past security findings | A failing test for every reverted remediation (F1–F13, H1) |
| [TEST-3](TEST-3.md) | Characterisation (golden-master) tests | Golden-output tests for every Core feature |
| [TEST-4](TEST-4.md) | CLI contract, packaged JAR and protocol tests | Exit codes, JSON Schemas, shaded JAR, serve/mcp protocols |
| [TEST-5](TEST-5.md) | Nightly deep tests | Fuzzing, engine differential, scale (reuses the [DEP-2](../deployment/DEP-2.md) benchmark), mutation |
| [TEST-6](TEST-6.md) | GUI smoke tests | Every GUI tab loads and runs headlessly |
