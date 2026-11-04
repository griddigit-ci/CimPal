#!/usr/bin/env bash
# Copyright (c) 2020-2026 gridDigIt Kft.
# Licensed under the EUPL-1.2-or-later.
# SPDX-License-Identifier: EUPL-1.2+
#
# The /v1 job API step by step with curl: upload, submit, poll, result, download
# (docs/guide/client-demo.md). Run from deploy/docker-compose after setup.sh and
# "docker compose up -d". BASE=http://localhost:7474 for the plain-HTTP variant.
set -euo pipefail
BASE=${BASE:-https://localhost:8443}
token=$(head -n 1 secrets/token | tr -d '\r\n')
auth=(-H "Authorization: Bearer $token")
# -k: the demo proxy signs with its own local CA; a real deployment has a trusted certificate.
curl=(curl -sS -k --fail-with-body)
py=${PYTHON:-$(command -v python || command -v python3)}
field() { "$py" -c "import json,sys; print(json.load(sys.stdin)$1)"; }

echo "1. Is the server ready? (no token needed)"
"${curl[@]}" "$BASE/ready"; echo

echo
echo "2. Upload the inputs as one ZIP"
"$py" -c "
import pathlib, zipfile
root = pathlib.Path('inputs')
with zipfile.ZipFile('inputs.zip', 'w', zipfile.ZIP_DEFLATED) as z:
    for p in sorted(root.rglob('*')):
        if p.is_file():
            z.write(p, p.relative_to(root).as_posix())
"
upload=$("${curl[@]}" "${auth[@]}" -H 'Content-Type: application/zip' --data-binary @inputs.zip "$BASE/v1/uploads")
echo "$upload"
uploadId=$(echo "$upload" | field "['uploadId']")

echo
echo "3. Submit a validation job on the upload: the answer comes at once, with a job ID"
job=$("${curl[@]}" "${auth[@]}" -H 'Content-Type: application/json' "$BASE/v1/jobs" -d "{
  \"command\": \"validate\", \"inputUpload\": \"$uploadId\", \"label\": \"client demo\",
  \"config\": {\"workflow\": \"mapping\", \"mappingCsv\": \"mapping.csv\", \"modelsDir\": \"models\",
             \"constraintsRoot\": \"constraints\", \"xmlBase\": \"http://example.com/data\"}}")
echo "$job"
jobId=$(echo "$job" | field "['jobId']")

echo
echo "4. Poll until it has finished"
while :; do
  status=$("${curl[@]}" "${auth[@]}" "$BASE/v1/jobs/$jobId" | field "['status']")
  echo "   status: $status"
  case "$status" in queued|running) sleep 1 ;; *) break ;; esac
done

echo
echo "5. The result: the same JSON summary the command line prints"
"${curl[@]}" "${auth[@]}" "$BASE/v1/jobs/$jobId/result" | field "['totals']"

echo
echo "6. The output files, then all of them as one ZIP (outputs.zip)"
"${curl[@]}" "${auth[@]}" "$BASE/v1/jobs/$jobId/files"; echo
"${curl[@]}" "${auth[@]}" -o outputs.zip "$BASE/v1/jobs/$jobId/archive"
"$py" -c "import zipfile; print(zipfile.ZipFile('outputs.zip').namelist())"
