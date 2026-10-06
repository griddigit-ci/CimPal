<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->
# Versioning and support

*Last reviewed 2026-10-06 · Describes CimPal 2026.10.6.1 and later*

<!-- maintainer review: this page is a DRAFT written in DEP-4. The policy statements below
     (stability promises, deprecation period, supported versions, response times, vulnerability
     contact) are proposals for gridDigIt to confirm or change. Remove this comment and the
     "(draft)" markers once reviewed, and record the review in docs/plans/deployment/DEP-4.md. -->

## Version numbers

CimPal releases are named by date: **`YYYY.M.D.N`**, e.g. `2026.10.6.1`, the first release on 6 October 2026. A higher number is always newer. The same version names:
- the GitHub release with its files (`CimPal-CLI.jar`, `CimPal.jar`, `CimPal.exe`);
- the container image `ghcr.io/griddigit-ci/cimpal:<version>`;
- what `java -jar CimPal-CLI.jar --version` prints.

The image tag `latest` points to the newest release. For anything automated, pin a version.

## What stays stable (draft)

These are the interfaces scripts and pipelines rely on. Within them, changes are **additive**: new options, new config keys and new JSON fields may appear, and existing ones keep their meaning.

| Interface | Promise (draft) |
|---|---|
| Command names and their options | Not removed or renamed without a deprecation period |
| Config file keys | As options; unknown keys keep being ignored, so newer config files still load in older versions |
| Exit codes `0`, `1`, `2`, `3` | Their meaning does not change |
| JSON output with a `schema` id (`cimpal-validate-summary/1`, `cimpal-compare-result/1`, `cimpal-compare-instances-result/1`) and the `stats` object | Fields may be added. Removing or changing a field means a new schema id (`…/2`). |
| Container image: user UID 10001, working folder `/data`, entrypoint runs the CLI | Not changed without a deprecation period |
| HTTP API of `serve` | Not yet a stable interface. The versioned `/v1` API (planned, DEP-5) will be. |
| MCP tool names and arguments | Follow the CLI options they map to |

Not covered: the text of log and progress messages, the layout of Excel reports, and internal Java APIs.

## Deprecation (draft)

When an option, key or behaviour has to go:
1. The release notes announce it, and CimPal prints a warning on stderr when it is used.
2. It keeps working for **at least three months and two releases** after the announcement.
3. Only then is it removed, and the release notes say so.

## Supported versions (draft)

- **Fixes:** bug fixes and security fixes go into the **newest release**. There are no patch releases for older versions; upgrade to get a fix.
- **Java:** the Java version a release needs is stated in its release notes. It is Java 25 today.

## Getting help (draft)

- **Questions and bugs:** [GitHub issues](https://github.com/griddigit-ci/CimPal/issues). Include the version, the command, the exit code and stderr, with confidential names removed.
- **Support by email:** cimpal@griddigit.eu, the support address given in the project README. The website is https://cimpal.app/. <!-- maintainer review: say whether this address also covers commercial support agreements -->

## Reporting a vulnerability

Please **do not** open a public issue for a security problem.
- **How:** report it privately through GitHub's private vulnerability reporting on the repository (Security tab → "Report a vulnerability"), or by email to cimpal@griddigit.eu. <!-- maintainer review: confirm private vulnerability reporting is enabled for griddigit-ci/CimPal and that security reports may go to cimpal@griddigit.eu; SEC-4 adds SECURITY.md with the final wording -->
- **What to include:** the version, what an attacker can do, and how to reproduce it.
- **Response (draft):** gridDigIt acknowledges a report within five working days and agrees a disclosure date with the reporter.

The current security self-assessment is [SECURITY-SELF-ATTESTATION.md](../../SECURITY-SELF-ATTESTATION.md); see [Security for operators](security.md) for how CimPal limits what it reads, writes and connects to.
