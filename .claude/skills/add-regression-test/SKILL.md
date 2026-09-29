---
description: Write a failing-first JUnit regression test that pins a bug fix or security finding in CimPal. Use when fixing a bug, closing a security finding (TEST-2), or changing security-sensitive code.
argument-hint: <finding id, bug description or Class#method>
---
<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# Add a regression test

Target: $ARGUMENTS

1. **Pin down the behaviour.** Identify the class and method that carry the fix or control. For a
   security finding, read its entry in `SECURITY-SELF-ATTESTATION.md` §4 and name the input that
   used to be accepted and must now be refused (or the reverse).
2. **Follow `.claude/rules/testing.md`:** JUnit Jupiter, no network, `@TempDir`, minimal synthetic
   fixtures under `src/test/resources/fixtures/<feature>/`, deterministic assertions.
3. **Place the test** in the module and package of the class under test:
   `<module>/src/test/java/<same package>/<Class>Test.java`. Extend the existing test class if
   there is one. Name the method after the behaviour, e.g.
   `refusesImportFromHostOutsideAllowlist`, and add a one-line comment linking the finding or
   issue (e.g. `// Attestation Finding 2 (SSRF via owl:imports)`).
4. **Prove it bites.** Temporarily revert or disable the fix (or reason concretely about why the test
   would fail without it), run `mvn -B -pl <module> -am test -Dtest=<Class>Test`, confirm it
   fails for the right reason, then restore the fix and confirm it passes. Never commit the
   reverted state.
5. **Security controls fail closed.** Assert the error or refusal (exception type, exit code,
   row error), not only the absence of a result.
6. Report: the test file and method, what it guards, and the red/green evidence from step 4.
