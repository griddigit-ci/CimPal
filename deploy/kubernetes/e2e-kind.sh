#!/usr/bin/env bash
# Copyright (c) 2020-2026 gridDigIt Kft.
# Licensed under the EUPL-1.2-or-later.
# SPDX-License-Identifier: EUPL-1.2+
#
# End to end on kind (DEP-6): a kind cluster with ingress-nginx, these manifests with a locally
# built image, a self-signed certificate for cimpal.example.com, and a sparql job submitted
# through the Ingress with curl. CI runs it (.github/workflows/integrations.yml); it also works
# on Docker Desktop with kind installed. Needs: docker, kind, kubectl, openssl, curl, python3.
#
#   IMAGE=cimpal:e2e deploy/kubernetes/e2e-kind.sh      # KEEP=1 leaves the cluster running
set -euo pipefail

IMAGE=${IMAGE:-cimpal:e2e}
CLUSTER=${CLUSTER:-cimpal-e2e}
# controller-v1.12.1, by commit, so a moved tag can't change what is applied.
INGRESS_NGINX_COMMIT=64780b1fed3af99f4eccbc3fdad7ad785e8a83b6
HOST=cimpal.example.com
here=$(cd "$(dirname "$0")" && pwd)
work=$(mktemp -d)

cleanup() {
    if [ "${KEEP:-}" != "1" ]; then
        kind delete cluster --name "$CLUSTER" >/dev/null 2>&1 || true
    fi
    rm -rf "$work"
}
trap cleanup EXIT

echo "== kind cluster with ingress on port 443"
cat > "$work/kind.yaml" <<EOF
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
nodes:
  - role: control-plane
    kubeadmConfigPatches:
      - |
        kind: InitConfiguration
        nodeRegistration:
          kubeletExtraArgs:
            node-labels: "ingress-ready=true"
    extraPortMappings:
      - containerPort: 443
        hostPort: 443
        protocol: TCP
EOF
kind create cluster --name "$CLUSTER" --config "$work/kind.yaml" --wait 180s
kind load docker-image "$IMAGE" --name "$CLUSTER"
kubectl apply -f "https://raw.githubusercontent.com/kubernetes/ingress-nginx/${INGRESS_NGINX_COMMIT}/deploy/static/provider/kind/deploy.yaml"
kubectl -n ingress-nginx wait --for=condition=ready pod -l app.kubernetes.io/component=controller --timeout=300s

echo "== secrets (token and TLS certificate), created outside the manifests"
kubectl apply -f "$here/namespace.yaml"
token=$(openssl rand -hex 32)
printf '%s\n' "$token" > "$work/token"
kubectl -n cimpal create secret generic cimpal-token --from-file=token="$work/token"
openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj "/CN=$HOST" -addext "subjectAltName=DNS:$HOST" \
    -keyout "$work/tls.key" -out "$work/tls.crt" 2>/dev/null
kubectl -n cimpal create secret tls cimpal-tls --cert "$work/tls.crt" --key "$work/tls.key"

echo "== manifests with the local image"
cp -r "$here" "$work/k8s"
name=${IMAGE%%:*}
tag=${IMAGE##*:}
sed -i "s|    newTag: latest|    newName: $name\n    newTag: $tag|" "$work/k8s/kustomization.yaml"
kubectl apply --dry-run=server -k "$work/k8s"
kubectl apply -k "$work/k8s"
kubectl -n cimpal rollout status deployment/cimpal --timeout=300s
pod=$(kubectl -n cimpal get pod -l app.kubernetes.io/name=cimpal -o jsonpath='{.items[0].metadata.name}')
printf '<http://example.org/a> <http://example.org/p> "x" .\n' > "$work/model.ttl"
kubectl -n cimpal cp "$work/model.ttl" "$pod:/data/model.ttl"

c() {
    curl -sS --resolve "$HOST:443:127.0.0.1" --cacert "$work/tls.crt" "$@"
}
auth=(-H "Authorization: Bearer $token")

echo "== readiness and token through the Ingress"
for _ in $(seq 1 30); do
    c -o /dev/null -w '%{http_code}' "https://$HOST/ready" | grep -q 200 && break
    sleep 2
done
test "$(c -o /dev/null -w '%{http_code}' "https://$HOST/ready")" = 200
test "$(c -o /dev/null -w '%{http_code}' "https://$HOST/v1/jobs")" = 401

echo "== the NetworkPolicy keeps other pods away from serve"
if kubectl run np-probe --rm -i --restart=Never --image=busybox:1.37 -n default --quiet -- \
        wget -q -T 5 -O /dev/null http://cimpal.cimpal.svc/health 2>/dev/null; then
    echo "note: this cluster's network plugin doesn't enforce NetworkPolicy"
else
    echo "blocked, as intended"
fi

echo "== a sparql job: submit, poll, result"
job=$(c "${auth[@]}" -H 'Content-Type: application/json' \
    -d '{"command":"sparql","label":"e2e","config":{"models":["/data/model.ttl"],"query":"SELECT ?o WHERE { ?s ?p ?o }"}}' \
    "https://$HOST/v1/jobs")
id=$(printf '%s' "$job" | python3 -c 'import json,sys; print(json.load(sys.stdin)["jobId"])')
status=queued
for _ in $(seq 1 60); do
    status=$(c "${auth[@]}" "https://$HOST/v1/jobs/$id" | python3 -c 'import json,sys; print(json.load(sys.stdin)["status"])')
    case "$status" in queued|running) sleep 2 ;; *) break ;; esac
done
echo "job $id: $status"
test "$status" = succeeded
result=$(c "${auth[@]}" "https://$HOST/v1/jobs/$id/result")
echo "result: $result"
printf '%s' "$result" | python3 -c 'import json,sys; r=json.load(sys.stdin); assert r["rows"]==[{"o":"x"}], r'
c "${auth[@]}" "https://$HOST/metrics" | grep -q 'cimpal_jobs_finished_total{status="succeeded"} 1'

echo "== JSON log lines, and no token in them"
logs=$(kubectl -n cimpal logs "$pod")
printf '%s\n' "$logs" | tail -n 5
printf '%s\n' "$logs" | python3 -c 'import json,sys; [json.loads(l) for l in sys.stdin if l.strip()]'
if printf '%s' "$logs" | grep -q "$token"; then echo "token found in the log" >&2; exit 1; fi

echo "== SIGTERM: the pod stops cleanly"
kubectl -n cimpal delete pod "$pod" --wait=true --timeout=120s
echo "E2E OK"
