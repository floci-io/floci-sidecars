# Floci Sidecars

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

Engines that a local cloud emulator cannot ship inside its own image, packaged as small,
stateless HTTP services. An emulator such as [Floci](https://github.com/floci-io/floci) starts a
sidecar lazily over the Docker socket, probes `GET /health`, calls its `/v1/*` endpoints, and
stops it on shutdown. A sidecar carries no emulator vocabulary, so any project that can run a
container can use one.

Every sidecar in this repository implements the [sidecar contract](docs/contract.md).

## Images

| Sidecar | Image | Port | What it does |
|---|---|---|---|
| [cedar](cedar/) | `floci/floci-sidecar-cedar` | 8180 | Cedar 4 policy parsing, schema validation and authorization (Amazon Verified Permissions) |

Images are published to Docker Hub and mirrored to `public.ecr.aws/floci/`, tagged with the
sidecar's own semantic version and `latest`, for `linux/amd64` and `linux/arm64`.

```bash
docker run --rm -p 8180:8180 floci/floci-sidecar-cedar:1.0.0
curl -s localhost:8180/health
```

## Layout

```
docs/contract.md   the contract every sidecar implements
sidecar-core/      Java module: server bootstrap, health endpoint, JSON plumbing
cedar/             one directory per sidecar: pom.xml, src/, Dockerfile, API.md, version.txt
```

Each sidecar is versioned and released on its own. A change under `cedar/` produces a
`cedar-vX.Y.Z` tag and a new `floci/floci-sidecar-cedar` image; nothing else is rebuilt.
`sidecar-core` is an internal module that ships inside each image and is never published on
its own.

## Build

```bash
./mvnw verify                                   # every module
./mvnw -pl cedar -am verify                     # one sidecar and what it needs
docker build -f cedar/Dockerfile -t floci-sidecar-cedar:local .   # always from the repo root
```

## Adding a sidecar

1. Copy `cedar/` to `<name>/` and change the artifact id, main class, port and Dockerfile
   labels. Register only `/v1/*` handlers; `SidecarServer` from `sidecar-core` provides
   `PORT`, `/health`, JSON parsing and error mapping.
2. Add the module to the root `pom.xml`, the paths to `.dockerignore`, and the package to
   `release-please-config.json` and `.release-please-manifest.json` (start at `0.1.0`).
3. Add a build matrix entry in `.github/workflows/ci.yml` and `release.yml`.
4. Document the endpoints in `<name>/API.md` and add a row to the table above.
5. Keep the API free of emulator vocabulary: a sidecar answers a generic question (evaluate this
   policy, execute this query); the emulator maps its own service semantics onto that.

## Releases

[release-please](https://github.com/googleapis/release-please) opens one release pull request per
sidecar from its Conventional Commits. Merging it tags `<name>-vX.Y.Z`, and the release workflow
builds and pushes that sidecar's image. A prerelease can be cut from any branch with the
`Release` workflow's manual trigger, which is how a consumer pins an unreleased change while
its own pull request is open.

## Licence

MIT. See [LICENSE](LICENSE).
