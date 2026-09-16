# Floci Sidecars

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

Engines that a local cloud emulator cannot ship inside its own image, packaged as small,
stateless HTTP services. An emulator such as [Floci](https://github.com/floci-io/floci) starts a
sidecar lazily over the Docker socket, probes `GET /health`, calls its `/v1/*` endpoints, and
stops it on shutdown. A sidecar carries no emulator vocabulary, so any project that can run a
container can use one.

Every sidecar in this repository implements the [sidecar contract](docs/contract.md). Sidecars
are [Quarkus](https://quarkus.io) applications compiled to native executables with GraalVM
(Mandrel), so an image is a few tens of megabytes and answers `/health` within milliseconds.

## Images

| Sidecar | Image | Port | What it does |
|---|---|---|---|
| [cedar](cedar/) | `floci/floci-sidecar-cedar` | 8180 | Cedar 4 policy parsing, schema validation and authorization (Amazon Verified Permissions) |

Images are published to Docker Hub, tagged with the
sidecar's own semantic version and `latest`, for `linux/amd64` and `linux/arm64`.

```bash
docker run --rm -p 8180:8180 floci/floci-sidecar-cedar:1.0.0
curl -s localhost:8180/health
```

## Layout

```
docs/contract.md   the contract every sidecar implements
sidecar-core/      Quarkus providers shared by every sidecar: /health, the error envelope, JSON helpers
cedar/             one directory per sidecar: pom.xml, src/, Dockerfile, Dockerfile.package, API.md, version.txt
```

Each sidecar is versioned and released on its own. A change under `cedar/` produces a
`cedar-vX.Y.Z` tag and a new `floci/floci-sidecar-cedar` image; nothing else is rebuilt.
`sidecar-core` is an internal module that ships inside each image and is never published on
its own.

## Build

```bash
./mvnw verify                                   # every module, on the JVM
./mvnw -pl cedar -am verify -Dnative            # native executable + the *IT tests against it (needs GraalVM or Mandrel)
docker build -f cedar/Dockerfile -t floci-sidecar-cedar:local .   # native build inside the Mandrel image, from the repo root
```

A native build needs `native-image` on the PATH (GraalVM or Mandrel for Java 25) and about
4 GB of memory; the Docker path needs nothing but Docker with at least 6 GB for the daemon.
`cedar/Dockerfile.package` is what CI uses: it only copies an executable that CI already built
on a runner of each architecture into `native/<arch>/`.

A sidecar that wraps a native library ships exactly one platform build of it next to the
executable. Cedar's `pom.xml` unpacks the right `libcedar_java_ffi` for the build host from the
cedar-java uber jar and hands it to the sidecar through `CEDAR_JAVA_FFI_LIB`; the image sets the
same variable. Running the executable by hand needs it too:

```bash
CEDAR_JAVA_FFI_LIB=$PWD/cedar/target/cedar-native/jne/macos/aarch64/libcedar_java_ffi.dylib ./cedar/target/sidecar-runner
```

## Adding a sidecar

1. Copy `cedar/` to `<name>/` and change the artifact id, `sidecar.name` and port in
   `application.yml`, and the Dockerfile labels. Write only `/v1/*` JAX-RS resources taking the
   body as a `String` through `Json.body`; `sidecar-core` provides `/health`, the error envelope
   and the `PORT` mapping. Throw `IllegalArgumentException` for a bad request, or expose a
   `BadRequestTypes` bean for your engine's own exception types.
2. Add the module to the root `pom.xml`, the paths to `.dockerignore`, and the package to
   `release-please-config.json` and `.release-please-manifest.json` (start at `0.1.0`).
3. Add the directory to the `native` matrix in `.github/workflows/ci.yml` and a
   `<name>--version` output line in `release.yml`. Anything the native image needs (reflection
   registrations, `jni-config.json`, `--initialize-at-run-time`) lives in the sidecar's own
   `src/main/resources`, never in `sidecar-core`.
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

MIT. See [LICENSE](LICENSE). Contributions are welcome: see [CONTRIBUTING.md](CONTRIBUTING.md),
the [Code of Conduct](CODE_OF_CONDUCT.md) and [MAINTAINERS.md](MAINTAINERS.md).
