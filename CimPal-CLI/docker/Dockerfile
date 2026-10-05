# Copyright (c) 2020-2026 gridDigIt Kft.
# Licensed under the EUPL-1.2-or-later.
# SPDX-License-Identifier: EUPL-1.2+
#
# Runtime image for the CimPal CLI: the Temurin 25 JRE plus the already-built fat JAR. Nothing is
# compiled here. Build from the repository root after packaging the CLI:
#
#   mvn -B -pl CimPal-CLI -am package -DskipTests
#   docker build -f CimPal-CLI/docker/Dockerfile -t cimpal:dev .
#
# The release workflow builds this file with the CimPal-CLI.jar it has just published, so the
# image carries exactly the released bytes. Usage: docs/cli/docker.md.

# Pinned by digest (multi-arch index). Bump the digest together with the tag.
FROM eclipse-temurin:25-jre-noble@sha256:693fdaf83831eeeefd9709eae44c8b8706622652f972cf5903bd0e481bbf6ad3

# Non-root user. /home/cimpal belongs to it alone. Any other UID (the base image also has root and
# ubuntu, UID 1000; Linux hosts pass `--user "$(id -u):$(id -g)"` to own bind-mount output) gets a
# private temporary home from entrypoint.sh, so CimPal's ~/.cimpal cache is never shared.
RUN groupadd --gid 10001 cimpal \
 && useradd --uid 10001 --gid cimpal --home-dir /home/cimpal --create-home --no-log-init \
        --shell /usr/sbin/nologin cimpal \
 && mkdir /data \
 && chown cimpal:cimpal /data

COPY --chmod=0755 CimPal-CLI/docker/entrypoint.sh /opt/cimpal/entrypoint.sh
COPY --chmod=0644 CimPal-CLI/target/CimPal-CLI.jar LICENSE.md /opt/cimpal/

LABEL org.opencontainers.image.title="CimPal CLI" \
      org.opencontainers.image.description="CimPal command-line tools for CIM/CGMES: SHACL validation, RDFS to SHACL, RDF conversion, SPARQL and comparison." \
      org.opencontainers.image.vendor="gridDigIt Kft." \
      org.opencontainers.image.licenses="EUPL-1.2+" \
      org.opencontainers.image.url="https://cimpal.app/" \
      org.opencontainers.image.source="https://github.com/griddigit-ci/CimPal" \
      org.opencontainers.image.documentation="https://github.com/griddigit-ci/CimPal/blob/master/docs/cli/docker.md"

ENV HOME=/home/cimpal
# Numeric, so that Kubernetes runAsNonRoot can verify it.
USER 10001:10001
# Mount the files to work on here; every path given to the CLI is a path inside the container.
WORKDIR /data
# No EXPOSE: `serve` speaks plain HTTP and belongs on the host loopback only, with the same port
# inside and outside (its Host check), so `docker run -P` must not publish it on every interface.
ENTRYPOINT ["/opt/cimpal/entrypoint.sh"]
CMD ["--help"]
