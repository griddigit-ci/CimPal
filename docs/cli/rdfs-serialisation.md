<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->
# `rdfs-serialisation`: RDFS serialisation info as Turtle

Instance data writers need to know, per profile, which classes are serialised with `rdf:about` and which values are enumerations. `rdfs-serialisation` reads that from RDFS profiles and writes it as Turtle, in the format of the `*_RDFSSerialisation.ttl` resources CimPal ships. It is the same as the GUI's **Tools ▸ RDFS Profiles ▸ Generate information from RDFS - needed for serialisation**, and both use `RdfsSerialisation` in Core.

Not exposed through `serve`, `mcp` or `run`.

---

## Quick example

```
java -jar CimPal-CLI.jar rdfs-serialisation ^
  --rdfs-files C:\Profiles\EQ.rdf,C:\Profiles\SSH.rdf ^
  --output C:\Profiles\CGMES30_RDFSSerialisation.ttl
```

---

## All flags

| Flag | Type | Default | Description |
|---|---|---|---|
| `--config` | file path | — | JSON config file (keys `rdfsFiles` as an array, `output`). Flags override it; relative paths resolve against its folder. |
| `--rdfs-files` | comma-separated paths | — | RDFS profiles (RDF/XML `.rdf`). **Required.** |
| `--output` | file path | — | The `.ttl` file to write. Parent folders are created; an existing file is replaced. **Required.** |
| `--dry-run` | flag | off | Print the resolved configuration and exit. |

---

## What it writes

For each profile, with `<ns>` the profile IRI followed by `#`:

- **`<ns>RdfAbout a rdfs:Class`:** its `owl:members` are the classes with `cims:stereotype "Description"`.
- **`<ns>RdfEnum a rdfs:Class`:** its `owl:members` are the values of every class with the `enumeration` stereotype.
- **Prefixes:** the profile's short name is a prefix for `<ns>`. `cim` is copied from the profile, plus the first of `entsoe`, `eu` or `nc` it declares.

The profile IRI and short name come from the `owl:Ontology` header (`owl:versionIRI`, `dcat:keyword`; RDFS 2020). For CGMES 2.4 profiles (RDFS 2019) they come from the `entsoeURI*` and `shortName` items' `cims:isFixed` values.

A profile with no such header is an error (exit 2, naming the file). So is a profile IRI that isn't a valid absolute IRI, and a short name that can't be a Turtle prefix. Both come from the RDFS as text and are written into the output. The GUI version used to write it under the namespace `null#`, or under the previous profile's namespace.

---

## Exit codes

| Code | Meaning |
|---|---|
| 0 | File written |
| 2 | Bad input: a flag missing, a file not found, unreadable, unparseable or JSON-LD, a profile without a header or with an invalid IRI or keyword, an `--output` that isn't `.ttl`, or an unreadable config |
| 3 | Internal error, e.g. the file can't be written |
