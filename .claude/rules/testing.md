---
paths:
  - "**/src/test/**"
---
<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# Testing rules

- **JUnit Jupiter only** (`org.junit.jupiter`). Test classes are named `<ClassUnderTest>Test` and live in
  the same package as the class under test.
- **No network.** Tests never make outbound connections. Serve remote content from a local stub,
  or exercise the code path that fails closed. A test that needs the internet is a bug.
- **Temporary files** go under JUnit `@TempDir`. Never write into the source tree, the user's home
  directory, or fixed paths.
- **Fixtures** live under `src/test/resources/fixtures/<feature>/`, where `<feature>` is a short
  kebab-case name, e.g. `fixtures/mapping-validation/`. Keep them minimal and synthetic. Real
  customer or ENTSO-E conformity data only with an explicit decision in `docs/plans/`.
- **Snapshots (golden-master outputs)** change only when the run has `-Dsnapshot.update=true`. A
  normal test run never rewrites expected output. When you update a snapshot, say so in the commit
  message and explain why the output changed.
- **Core tests run on the classpath** (`useModulePath=false` in `CimPal-Core/pom.xml`), so they can
  reach package-private members. Don't add test-only `opens` to `module-info.java`.
- **Determinism:** no reliance on wall-clock time, locale, map iteration order or thread timing. Pass
  fixed timestamps and sort before you compare.
- Assert on behaviour (outputs, exit codes, report contents), not on log text.
- Run with `mvn -B -pl <module> -am test` (`-Dtest=ClassName` for one class) and keep the full
  `mvn -B verify` green.
