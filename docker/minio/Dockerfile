# Build the same upstream release used by docker-compose.yml when its
# historical prebuilt image is unavailable. No third-party MinIO binary.
FROM golang:1.24.8-bookworm AS builder
ENV CGO_ENABLED=0 GOBIN=/out
RUN go install -trimpath github.com/minio/minio@RELEASE.2025-09-07T16-13-09Z
RUN mkdir -p /runtime/data /runtime/tmp && chmod 1777 /runtime/tmp

FROM scratch
LABEL org.opencontainers.image.source="https://github.com/minio/minio" \
      org.opencontainers.image.version="RELEASE.2025-09-07T16-13-09Z" \
      org.opencontainers.image.licenses="AGPL-3.0-only"
COPY --from=builder /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
COPY --from=builder /out/minio /minio
COPY --from=builder --chown=10001:10001 /runtime/data /data
COPY --from=builder /runtime/tmp /tmp
USER 10001:10001
EXPOSE 9000 9001
ENTRYPOINT ["/minio"]
CMD ["server", "/data", "--console-address", ":9001"]
