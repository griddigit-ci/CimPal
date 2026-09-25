---
paths:
  - "CimPal-CLI/src/main/java/eu/griddigit/CimPal/cli/command/ServeCommand.java"
  - "CimPal-CLI/src/main/java/eu/griddigit/CimPal/cli/command/McpCommand.java"
  - "CimPal-CLI/src/main/java/eu/griddigit/CimPal/cli/command/RunCommand.java"
  - "**/ValidationTools.java"
  - "**/ExcelTools.java"
  - "**/ModelFactory.java"
  - "**/PythonShaclValidator.java"
  - "**/ComparisonCsvWriter.java"
  - "**/InstanceDataFactory.java"
  - "**/WizardContext.java"
  - "**/RDFVisualisationController.java"
  - "CimPal-Main/src/main/java/eu/griddigit/cimpal/main/ai/**"
---
<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# Security rules for security-sensitive code

These files handle untrusted input, network access, archive extraction, process execution or the
`serve`/`mcp`/`run` entry points. `SECURITY-SELF-ATTESTATION.md` (§4 findings, §5.3 item 5) describes
the controls they carry. Treat every change here as a material change.

- **Never weaken the egress policy.** Don't widen `ALLOWED_REMOTE_HOSTS` or relax either tier of
  the egress gate in `ValidationTools`: first tier (scheme, host allowlist, no embedded
  credentials), second tier `requirePublicHost` (no loopback, link-local or private addresses), and
  no redirects on credentialed requests. Don't add an outbound path that bypasses the gate, unless
  the current plan in `docs/plans/` explicitly says to.
- **Fail closed.** A failed `owl:imports` fetch, download, parse or policy check surfaces as an error
  or a row failure. It never counts as a pass and is never skipped silently. A false-clean validation
  is the worst outcome here.
- **Processes:** build commands as argument lists (`ProcessBuilder(List)`), never by string
  concatenation or through a shell. Don't resolve new executables from `PATH` without a plan entry.
- **Archives:** every extraction path keeps the limits in `ModelFactory` (`MAX_ZIP_ENTRIES`,
  `MAX_TOTAL_UNCOMPRESSED_BYTES`, `MAX_ZIP_NESTING_DEPTH`). Entry names never become filesystem
  paths without a check that the normalised target stays inside the destination.
- **Filesystem:** write to caller-supplied or per-user directories (`Files.createTempDirectory`), not
  shared fixed paths. Derive cache filenames from hashes, never from URLs or user text.
- **Reports:** keep formula-injection neutralisation (see `ComparisonCsvWriter`) on every emitted
  CSV/Excel cell that can carry input text.
- **Logs and errors:** no credentials, tokens or full file contents. Pass untrusted strings through
  the existing log sanitiser (`forLog`) and keep messages free of stack traces for end users.
- **serve / mcp / run:** don't add endpoints, tools or pipeline steps that accept arbitrary paths,
  shell out, or nest `serve`/`mcp`/`run`. SEC-1 and SEC-2 in `docs/plans/` are hardening these.
- **Every change needs a regression test** that fails without the change and passes with it
  (`/add-regression-test`). Run `/security-check-change` before you call the work done.
