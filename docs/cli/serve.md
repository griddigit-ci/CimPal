# `serve` — HTTP Daemon Command

Starts a local HTTP server that exposes every CimPal operation as a REST endpoint. The server accepts JSON request bodies (same format as command config files) and returns JSON responses.

**Why use the daemon instead of the CLI?**  
Every CLI invocation starts a fresh JVM and re-initialises Jena, the SHACL engine, and all loaded libraries. For a single run this overhead is negligible. For an automated validation agent that calls CimPal dozens or hundreds of times per session, keeping the JVM warm eliminates repeated cold-start cost.

---

## Quick start

```
java -jar CimPal-CLI.jar serve
```

The server starts on `localhost:7474`. To check it is alive:
```
curl http://localhost:7474/health
# {"status":"ok","version":"CimPal CLI 2026.9"}
```

Stop it cleanly:
```
curl -X POST http://localhost:7474/shutdown
```

Or press `Ctrl-C`.

---

## All flags

| Flag | Type | Default | Description |
|---|---|---|---|
| `--port` / `-p` | integer | `7474` | Port to listen on. |
| `--host` | string | `localhost` | Bind address. The default `localhost` means the server is not accessible from the network — only from processes on the same machine. |

---

## API reference

### Command endpoints — `POST /<command>`

Each subcommand is exposed as a `POST` endpoint. The request body is a JSON object with the same keys the command's config file accepts. The response is the JSON output the command produces (equivalent to running with `--format json`).

| Endpoint | Equivalent CLI command |
|---|---|
| `POST /validate` | `cimpal validate --format json --config <body>` |
| `POST /sparql` | `cimpal sparql --format json --config <body>` |
| `POST /convert` | `cimpal convert --config <body>` |
| `POST /compare` | `cimpal compare --format json --config <body>` |
| `POST /compare-instances` | `cimpal compare-instances --format json --config <body>` |
| `POST /rdfs2shacl` | `cimpal rdfs2shacl --config <body>` |
| `POST /organize` | `cimpal organize --config <body>` |
| `POST /excel2shacl` | `cimpal excel2shacl --config <body>` |
| `POST /gen-instances` | `cimpal gen-instances --config <body>` |
| `POST /manifest` | `cimpal manifest --config <body>` |

### Utility endpoints

| Endpoint | Method | Description |
|---|---|---|
| `/health` | `GET` | Returns `{"status":"ok","version":"..."}`. Use this to check the server is alive before sending work. |
| `/commands` | `GET` | Lists all available command and utility endpoints. |
| `/shutdown` | `POST` | Stops the server gracefully. |

---

## HTTP status codes

| Code | Meaning |
|---|---|
| 200 | Command ran successfully (including when violations were found — exit 0 or 1) |
| 400 | Bad request — invalid input, missing required field (exit 2) |
| 500 | Internal error — command crashed (exit 3) |
| 405 | Method not allowed — wrong HTTP verb for the endpoint |

The response body always contains JSON. On errors, it has `{"error":"..."}`. On 200, it contains the same JSON the `--format json` flag would produce on stdout.

---

## Example: validate a model set

```bash
curl -s -X POST http://localhost:7474/validate \
  -H "Content-Type: application/json" \
  -d '{
    "workflow": "mapping",
    "mappingCsv": "C:/Data/mapping.csv",
    "modelsDir": "C:/Data/models",
    "constraintsRoot": "C:/Data/constraints",
    "outputDir": "C:/Data/output",
    "datatypeMap": "CGMES30NC25",
    "xmlBase": "http://iec.ch/TC57/CIM100",
    "samples": 3
  }'
```

Response:
```json
{
  "schema": "cimpal-validate-summary/1",
  "run": { ... },
  "totals": {"conforming": 5, "violations": 42, "errors": 0, "total": 47},
  "hasViolations": true,
  "shapes": [
    {
      "shapeId": "http://example.org/shapes/EQ#VoltageLevel.highVoltageLimit",
      "constraint": "sh:MinCountConstraintComponent",
      "path": "http://iec.ch/TC57/CIM100#VoltageLevel.highVoltageLimit",
      "count": 15,
      "sampleFocusNodes": ["http://example.org#_vl1", "http://example.org#_vl2"]
    }
  ],
  "report": "C:/Data/output/validation_report__20260920_143022.xlsx"
}
```

---

## Example: run a SPARQL query

```bash
curl -s -X POST http://localhost:7474/sparql \
  -H "Content-Type: application/json" \
  -d '{
    "models": ["C:/Data/EQ.xml", "C:/Data/TP.xml"],
    "query": "SELECT ?s ?type WHERE { ?s a ?type } LIMIT 10",
    "xmlBase": "http://iec.ch/TC57/CIM100",
    "format": "json"
  }'
```

---

## File paths in requests

All file paths in request bodies must be **absolute paths**. The server and the caller share a filesystem (the daemon runs locally), so the files must be accessible from the machine where the server is running.

There is no file upload mechanism — the server reads from and writes to local paths that are specified in the request JSON.

---

## Thread safety and concurrency

The server uses a **single-threaded executor**: requests are processed one at a time. This avoids race conditions on the static flags in `ValidationTools` (debug mode, Turtle report export). For the intended use case — a local validation agent sending one request at a time — this is the right trade-off: a single validation run already saturates available CPU cores internally through its own worker pool.

If you need to run multiple pipelines concurrently, start multiple daemon instances on different ports.

---

## Integration with the agent

The MCP tool wrappers for the validation agent call the daemon endpoints. Typical usage:

```python
# Python example — send validate request, read shapes
import requests, json

resp = requests.post("http://localhost:7474/validate", json={
    "workflow": "mapping",
    "mappingCsv": mapping_csv,
    "modelsDir": models_dir,
    "constraintsRoot": constraints_root,
    "outputDir": output_dir,
    "datatypeMap": "CGMES30NC25",
    "xmlBase": "http://iec.ch/TC57/CIM100",
    "samples": 5
})
result = resp.json()
if result.get("hasViolations"):
    for shape in result.get("shapes", []):
        print(f"{shape['count']} violations on {shape['shapeId']}")
```

---

## Starting automatically

To keep the daemon running as a background process (Windows):

```powershell
Start-Process -FilePath "java" `
  -ArgumentList "-jar", "CimPal-CLI.jar", "serve", "--port", "7474" `
  -WindowStyle Hidden
```

Or add it to a startup script that runs before your agent session begins.
