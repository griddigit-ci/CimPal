# `mcp` — Model Context Protocol Server

Starts a Model Context Protocol (MCP) server that exposes all CimPal operations as typed tools a Claude agent can call directly — without shell invocations or subprocess management.

---

## Quick setup — Claude Desktop

1. Find your Claude Desktop config file:
   - **Windows:** `%APPDATA%\Claude\claude_desktop_config.json`
   - **macOS:** `~/Library/Application Support/Claude/claude_desktop_config.json`

2. Add the `cimpal` server block:

```json
{
  "mcpServers": {
    "cimpal": {
      "command": "java",
      "args": ["-jar", "C:/path/to/CimPal-CLI.jar", "mcp"]
    }
  }
}
```

3. Restart Claude Desktop. The CimPal tools appear automatically in Claude's tool palette.

A ready-to-edit template is at `CimPal-CLI/configs/claude-desktop-config.json`.

---

## All flags

| Flag | Type | Default | Description |
|---|---|---|---|
| `--debug` | flag | off | Write every MCP message to stderr. Use for troubleshooting; has no effect on the protocol channel. |

---

## Available tools

| Tool name | Equivalent CLI command | JSON response |
|---|---|---|
| `validate` | `cimpal validate --format json` | Totals + per-shape violation groups |
| `sparql` | `cimpal sparql --format json` | Column headers + rows |
| `compare` | `cimpal compare --format json` | Difference list |
| `compare_instances` | `cimpal compare-instances --format json` | Difference list |
| `convert` | `cimpal convert` | Exit code + output path |
| `rdfs_to_shacl` | `cimpal rdfs2shacl` | Exit code + output paths |
| `organize` | `cimpal organize` | Exit code |
| `excel_to_shacl` | `cimpal excel2shacl` | Exit code + output path |
| `gen_instances` | `cimpal gen-instances` | Exit code + output path |
| `manifest` | `cimpal manifest` | Exit code + manifest path |

---

## How tool arguments work

Each tool accepts a JSON object with the same keys as the command's config file. Everything documented in the command's `--help` and in `docs/cli/<command>.md` applies directly to the tool arguments.

**Example — validate tool:**
```json
{
  "workflow": "mapping",
  "mappingCsv": "/data/mapping.csv",
  "modelsDir": "/data/models",
  "constraintsRoot": "/data/constraints",
  "outputDir": "/data/output",
  "datatypeMap": "CGMES30NC25",
  "xmlBase": "http://iec.ch/TC57/CIM100",
  "samples": 5
}
```

**Example — sparql tool:**
```json
{
  "models": ["/data/EQ.xml", "/data/TP.xml"],
  "query": "SELECT ?s ?type WHERE { ?s a ?type } LIMIT 20",
  "xmlBase": "http://iec.ch/TC57/CIM100"
}
```

All file paths must be absolute — the MCP server runs as a subprocess of Claude Desktop and shares your local filesystem.

---

## Protocol details

The MCP server implements the [MCP 2024-11-05](https://modelcontextprotocol.io) specification using the **stdio transport** (newline-delimited JSON-RPC 2.0 on stdin/stdout).

Supported methods:
- `initialize` / `notifications/initialized`
- `tools/list`
- `tools/call`
- `ping`

Tool call responses use the standard MCP content format:
```json
{
  "content": [{"type": "text", "text": "<result JSON string>"}],
  "isError": false
}
```

`isError` is `true` when the command exits with code 2 (bad input) or 3 (internal error).

---

## Response format

All tool responses are JSON strings. For commands that support `--format json` (validate, sparql, compare, compare-instances), the response is the full structured JSON. For other commands, the response is a minimal envelope:

```json
{"exitCode": 0, "status": "OK"}
```

or on error:
```json
{"exitCode": 2, "output": "...", "isError": true}
```

---

## Memory configuration

Large CGMES models require significant heap. Configure JVM memory in the Claude Desktop config:

```json
{
  "mcpServers": {
    "cimpal": {
      "command": "java",
      "args": ["-Xmx8g", "-jar", "C:/path/to/CimPal-CLI.jar", "mcp"]
    }
  }
}
```

`-Xmx8g` gives the server 8 GB heap — enough for most full-grid CGMES 3.0 models with multiple concurrent validation workers.

---

## Debugging

Add `--debug` to log every MCP message exchange to stderr. Claude Desktop captures this in its log files:

```json
"args": ["-jar", "CimPal-CLI.jar", "mcp", "--debug"]
```

You can also test the MCP server manually by piping JSON-RPC messages:

```bash
echo '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' \
  | java -jar CimPal-CLI.jar mcp 2>/dev/null | python -m json.tool
```

---

## The validation agent loop (Part B of the plan)

The MCP tools are designed to support the closed-loop agent described in the project plan:

1. **Diagnose** — call `validate` with `samples: 5` to get per-shape violation groups
2. **Inspect** — call `sparql` to look up the focus nodes in the actual model data
3. **Compare** — call `compare` to diff profile versions and understand what changed
4. **Fix** — edit the SHACL `.ttl` or RDFS `.rdf` file
5. **Test** — call `gen_instances` to generate fixtures, call `validate` in manual mode
6. **Promote** — once fixtures pass, run `validate` in mapping mode against real data

Each iteration is a sequence of MCP tool calls. The agent reads the `shapes` array from the `validate` response to know which shapes to diagnose next.
