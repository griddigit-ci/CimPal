<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->
# Versioning and support

*Last reviewed 2026-10-22 · Describes CimPal 2026.10.6.1 and later*

## Version numbers

CimPal releases are named by date: **`YYYY.M.D.N`**, e.g. `2026.10.6.1`, the first release on 6 October 2026. A higher number is always newer. The same version names:
- the GitHub release with its files (`CimPal-CLI.jar`, `CimPal.jar`, `CimPal.exe`);
- the container image `ghcr.io/griddigit-ci/cimpal:<version>`;
- what `java -jar CimPal-CLI.jar --version` prints.

The image tag `latest` points to the newest release. For anything automated, pin a version.

## What stays stable

These are the interfaces scripts and pipelines rely on. Within them, changes are **additive**: new options, new config keys and new JSON fields may appear, and existing ones keep their meaning.

| Interface | Promise |
|---|---|
| Command names and their options | Not removed or renamed without a deprecation period |
| Config file keys | As options; unknown keys keep being ignored, so newer config files still load in older versions |
| Exit codes `0`, `1`, `2`, `3` | Their meaning does not change |
| JSON output with a `schema` id (`cimpal-validate-summary/1`, `cimpal-compare-result/1`, `cimpal-compare-instances-result/1`) and the `stats` object | Fields may be added. Removing or changing a field means a new schema id (`…/2`). |
| Container image: user UID 10001, working folder `/data`, entrypoint runs the CLI | Not changed without a deprecation period |
| [Job API `/v1`](../cli/serve.md#job-api-v1) of `serve`: its paths, request and response fields and status codes, as published in `/v1/openapi.json` | Fields and endpoints may be added. A breaking change means a new version (`/v2`), served next to `/v1` for at least the deprecation period. |
| Other HTTP endpoints of `serve` (the command endpoints `POST /<command>`) | Not a stable interface; use `/v1` for anything automated |
| MCP tool names and arguments | Follow the CLI options they map to |

Not covered: the text of log and progress messages, the layout of Excel reports, and internal Java APIs.

## Deprecation

When an option, key or behaviour has to go:
1. The release notes announce it, and CimPal prints a warning on stderr when it is used.
2. It keeps working for **at least three months and two releases** after the announcement.
3. Only then is it removed, and the release notes say so.

## Supported versions

- **Fixes:** bug fixes and security fixes go into the **newest release**. There are no patch releases for older versions; upgrade to get a fix.
- **Java:** the Java version a release needs is stated in its release notes. It is Java 25 today.

## Getting help

- **Questions and bugs:** [GitHub issues](https://github.com/griddigit-ci/CimPal/issues). Include the version, the command, the exit code and stderr, with confidential names removed.
- **Support by email:** cimpal@griddigit.eu, the support address given in the project README. The website is https://cimpal.app/.

## Reporting a vulnerability

Please **do not** open a public issue for a security problem.
- **How:** report it privately through GitHub's private vulnerability reporting on the repository (Security tab → "Report a vulnerability"), or by email to cimpal@griddigit.eu.
- **What to include:** the version, what an attacker can do, and how to reproduce it.
- **Response:** gridDigIt acknowledges a report within five working days and agrees a disclosure date with the reporter.

The current security self-assessment is [SECURITY-SELF-ATTESTATION.md](../../SECURITY-SELF-ATTESTATION.md); see [Security for operators](security.md) for how CimPal limits what it reads, writes and connects to.
