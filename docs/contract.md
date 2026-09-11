# Sidecar Contract v1

**Status:** stable once the first `contract: 1` image is published.
**Audience:** authors of a sidecar image, and authors of an emulator that runs one.

A sidecar is a stateless HTTP service that an emulator starts as a container when it first
needs it, calls over plain HTTP, and stops when it shuts down. This page is the contract between
the two. A sidecar that implements it runs under any emulator that implements it, with no
configuration beyond an image name.

The key words MUST, MUST NOT, SHOULD, SHOULD NOT and MAY are to be interpreted as described in
[RFC 2119](https://www.rfc-editor.org/rfc/rfc2119).

## The short version

Three things make an image a sidecar:

1. It listens for HTTP on `0.0.0.0` at the port in the `PORT` environment variable, falling back
   to its own default when `PORT` is unset.
2. It serves `GET /health` as JSON with `status`, `name`, `version` and `contract`.
3. It needs nothing from the host: no Docker socket, no volume, no host path, no network access
   at start.

Everything else on this page is detail.

## What the emulator gives the sidecar

| Variable | Example | Notes |
|---|---|---|
| `PORT` | `8180` | The port the sidecar MUST listen on. The emulator publishes and probes this port. |
| `SIDECAR_VERSION` | `1.2.0` | Set by the image itself at build time. The emulator MUST NOT override it. |

Nothing else is guaranteed. A sidecar MUST NOT depend on emulator-specific variables such as
`AWS_ENDPOINT_URL`, `AWS_REGION` or cloud credentials. A sidecar that needs configuration for a
feature (an object-store endpoint, a credential pair) documents its own variables in its `API.md`,
and MUST start and report healthy without them.

## What the sidecar gives the emulator

### The health endpoint

The sidecar MUST serve `GET /health` unauthenticated and respond within a second.

```json
{
  "status": "ok",
  "name": "cedar",
  "version": "1.2.0",
  "contract": "1"
}
```

| Field | Required | Meaning |
|---|---|---|
| `status` | yes | `ok` when the sidecar can serve requests; anything else means "still coming up" |
| `name` | yes | The sidecar's short name, identical to the `io.floci.sidecar.name` label |
| `version` | yes | The sidecar's own semantic version, identical to the image tag |
| `contract` | yes | The contract major this sidecar implements, `"1"` for this page |

How the emulator reads it:

| Response | Emulator's conclusion |
|---|---|
| `200`, JSON, `status` is `ok`, `contract` major it supports | ready |
| `200`, JSON, `status` is not `ok` | not ready, keep polling |
| `200`, JSON, `contract` major it does not support | fail fast: name the image, the reported version and the configuration key to change |
| `200`, body is not JSON | contract 0, a sidecar that predates this page: ready, with a one-time warning |
| anything else, or no response | not ready, keep polling |

### The API

- Every functional endpoint MUST live under `/v1/` (or `/v2/` and so on when the sidecar's own
  API changes incompatibly; that is the sidecar's API version, not the contract version).
- Endpoints MUST accept and return `application/json`.
- An error MUST be `{"error": "<message>"}` with `400` for a request the sidecar rejects
  (malformed body, missing field, invalid policy or query) and `500` for a failure the sidecar
  did not expect. `405` for a method the endpoint does not serve.
- The API MUST NOT use emulator or cloud-provider vocabulary. A sidecar answers a generic
  question (is this authorized, run this query, plan this document); mapping that onto a
  provider's service semantics is the emulator's job.
- The sidecar SHOULD be stateless: everything a request needs travels in the request. A sidecar
  that caches MUST produce identical results with a cold cache.

Each sidecar documents its endpoints and payloads in `<name>/API.md` in this repository.

### The container

- The sidecar MUST bind `0.0.0.0`, not `127.0.0.1`.
- The sidecar MUST NOT require the Docker socket, a mounted volume, a host path, or outbound
  network access to become healthy.
- The sidecar MUST run as a non-root user.
- The sidecar MUST tolerate being stopped and recreated at any time, and MUST NOT rely on
  anything it wrote to disk surviving.
- The sidecar SHOULD become healthy in well under thirty seconds; the emulator polls for that
  long before giving up.
- The image MUST be published for `linux/amd64` and `linux/arm64`.

## Self-description labels

The image MUST carry these OCI labels so an emulator can learn the port and health path before
the container starts, and can compare versions without running it:

```dockerfile
LABEL io.floci.sidecar.contract="1"
LABEL io.floci.sidecar.name="cedar"
LABEL io.floci.sidecar.version="1.2.0"
LABEL io.floci.sidecar.port="8180"
LABEL io.floci.sidecar.health-path="/health"
```

| Label | Required | Meaning |
|---|---|---|
| `contract` | yes | Contract major. Without it every other label is ignored. |
| `name` | yes | Short name, same as `name` in the health response. |
| `version` | yes | Semantic version, same as `version` in the health response and the image tag. |
| `port` | no | Default listen port when `PORT` is unset. Defaults to what the sidecar documents. |
| `health-path` | no | Defaults to `/health`. |

## Versioning

- The sidecar's version is its own semantic version. It is not tied to any emulator's version.
- Images are tagged `<version>` and `latest`. An emulator SHOULD pin an exact version and MUST
  NOT depend on `latest` in a default, because a cached `latest` is never re-pulled.
- The contract major changes only when this page changes incompatibly. A sidecar declares the
  major it implements in the health response and the label; an emulator declares the majors it
  accepts.

## Conformance checklist

- [ ] Listens on `0.0.0.0:$PORT`, with a documented default when `PORT` is unset
- [ ] Serves `GET /health` unauthenticated with `status`, `name`, `version`, `contract`
- [ ] `status` is `ok` only when requests can actually be served
- [ ] Every endpoint is under `/v1/`, speaks JSON, and errors as `{"error": ...}` with 400/405/500
- [ ] No emulator or cloud-provider vocabulary in the API
- [ ] Needs no Docker socket, no volume, no host path, no network to become healthy
- [ ] Runs as a non-root user
- [ ] Carries `io.floci.sidecar.contract`, `.name` and `.version` labels that match the health response
- [ ] Published for `linux/amd64` and `linux/arm64`, tagged `<version>` and `latest`
- [ ] Endpoints documented in `<name>/API.md`
