<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->
# `qocdc2xlsx`: QoCDC 3.2.1 rules to Excel

Lists the rules of a QoCDC 3.2.1 rule file (`UMLRestrictionRule` instances, RDF/XML) in a workbook. It is the same as the GUI's **Tools ▸ Quality Reports ▸ QoCDC 3.2.1 xml to Excel**, and both use `QocdcRules` in Core.

Not exposed through `serve`, `mcp` or `run`.

---

## Quick example

```
java -jar CimPal-CLI.jar qocdc2xlsx --input C:\QoCDC\QoCDC_v3.2.1.xml --output C:\QoCDC\QoCDC321.xlsx
```

---

## All flags

| Flag | Type | Default | Description |
|---|---|---|---|
| `--config` | file path | — | JSON config file (keys `input`, `output`). Flags override it; relative paths resolve against its folder. |
| `--input` | file path | — | The rule file (RDF/XML). Its relative IRIs resolve against `http://entsoe.eu/CIM/Extensions/CGM-BP/2020`. **Required.** |
| `--output` | file path | — | The `.xlsx` workbook to write. Parent folders are created; an existing file is replaced. **Required.** |
| `--dry-run` | flag | off | Print the resolved configuration and exit. |

---

## The workbook

One sheet, `QoCDC321`, one row per rule sorted by name, with the columns `Rule name`, `Rule severity`, `Rule description`, `Rule message` and `Rule level`. A missing value is `NA`. Values are written as their text, without a language tag. Formula-like text (a message starting with `=`) is stored as text.

---

## Exit codes

| Code | Meaning |
|---|---|
| 0 | Workbook written |
| 2 | Bad input: a flag missing, the file not found, unreadable or unparseable, no `UMLRestrictionRule` in it (a wrong or differently versioned file), an `--output` that isn't `.xlsx`, or an unreadable config |
| 3 | Internal error, e.g. the workbook can't be written |
