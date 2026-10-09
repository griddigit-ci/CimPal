<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->
# Security track

WP prefix `SEC`. Status of each WP is kept only in the master table in [`../README.md`](../README.md). Full rationale and threat model: [CimPal — Security & Testing Plan](https://claude.ai/code/artifact/19c44ed1-1c4d-48ab-be25-072492b6ffd5) (claude.ai doc).

| ID | Work package | Goal |
| --- | --- | --- |
| [SEC-1](SEC-1.md) | Harden serve | Close G1, G4 (serve) and G6 |
| [SEC-2](SEC-2.md) | Allowed roots and SPARQL SERVICE | Close G2 and G3 |
| [SEC-3](SEC-3.md) | MCP output hygiene and external engines | Close G5 and G9 |
| [SEC-4](SEC-4.md) | Governance and re-attestation | Close G8 |
| [SEC-5](SEC-5.md) | Fix the findings reported by TEST-2 | Close the TEST-2 findings |

Server changes in the deployment track ([DEP-5](../deployment/DEP-5.md) to [DEP-8](../deployment/DEP-8.md)) must keep every guarantee listed here.

## Security gaps referenced by the WPs

Severities are estimates until a WP confirms them.

| ID | Gap | Severity (estimate) | WP |
| --- | --- | --- | --- |
| G1 | `serve`: no auth, no Host/Origin/Content-Type check, unbounded body, open `/shutdown` | High | SEC-1 |
| G2 | `serve`/`mcp`/`run` accept any path; no allowed roots | High | SEC-2 |
| G3 | User SPARQL `SERVICE` may bypass the egress allowlist | Medium (verify) | SEC-2 |
| G4 | No timeouts, queue limits or memory guard in `serve`/`mcp` | Medium | SEC-1 (`serve` timeout and queue only; `mcp` limits and a memory guard still open) |
| G5 | MCP results carry untrusted text into the agent's context | Medium | SEC-3 |
| G6 | `run` can nest `serve`/`mcp`/`run` | Low | SEC-1 |
| G7 | CI only on tags; tag-pinned actions; no Dependabot; SBOM not published; unsigned exe | Medium | CI-1, CI-2 |
| G8 | Attestation stale and unsigned; no `SECURITY.md` | Medium (governance) | SEC-4 |
| G9 | External SHACL engines resolved from `PATH` | Low (verify) | SEC-3 |
