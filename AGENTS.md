Guidance for AI coding agents working in the Floci Sidecars repository.

## What this repository is

One directory per sidecar, plus `sidecar-core`, the shared Java bootstrap. A sidecar is a
stateless HTTP service that an emulator starts as a container. It must implement
`docs/contract.md` and must not know which emulator is calling it.

## Rules

- **One directory per sidecar.** `<name>/pom.xml`, `<name>/src`, `<name>/Dockerfile`,
  `<name>/API.md`, `<name>/version.txt`. Never put sidecar code in `sidecar-core`.
- **No emulator vocabulary in a sidecar's API.** No AWS, GCP or Azure service names, error
  codes or field names cross the HTTP boundary. Cedar answers `ALLOW`/`DENY`; the emulator turns
  that into its own response shape.
- **The contract is `docs/contract.md`.** A change to `/health`, the labels, `PORT` or the error
  envelope is a contract change: bump the contract major, update every sidecar, and say so in the
  commit body.
- **Versions live in `version.txt`, not in `pom.xml`.** release-please bumps `version.txt` and
  tags `<name>-vX.Y.Z`; the Docker build receives it as `SIDECAR_VERSION`. Maven versions stay
  `0.0.0-SNAPSHOT`.
- **Dockerfiles build from the repository root** (`docker build -f <name>/Dockerfile .`) and copy
  only what `.dockerignore` allows. When you add a sidecar, add its paths there.
- **Conventional Commits, scoped by sidecar**: `feat(cedar): ...`, `fix(core): ...`,
  `ci: ...`, `docs: ...`. release-please derives each sidecar's version from the commits that
  touch its directory.
- Do not add `Co-Authored-By` or any AI attribution trailer to commit messages.

## Code style (same as floci-io/floci)

- Java 25, 4-space indentation, K&R braces, always braces in conditionals.
- **No `var`.** Write the explicit type, including in enhanced-for and try-with-resources.
- Import the classes you use; no fully-qualified names inline, no wildcard imports in `src/main`.
- JBoss Logging in a field named `LOG`, parameterized `...v()` form, no string concatenation in
  log calls. No `printStackTrace`, no `System.out` in `src/main`.
- `java.time` for time. Switch expressions over switch statements. Pattern-matching `instanceof`.
- Never leave a `catch` block empty.
- No em-dashes anywhere, in code, docs or commit messages. Use colons, commas or periods.

## Tests

- JUnit 5, Hamcrest matchers. Test methods are camelCase sentences or `method_scenario_expectation`.
- A sidecar's tests start it in-process on port 0 and speak HTTP to it; that is the same surface
  the emulator uses.
- `./mvnw verify` must pass before a commit. `docker build -f <name>/Dockerfile .` must succeed
  when the Dockerfile or `.dockerignore` changed.

## Build & run

    ./mvnw verify
    ./mvnw -pl cedar -am verify
    docker build -f cedar/Dockerfile --build-arg SIDECAR_VERSION=0.0.0-local -t floci-sidecar-cedar:local .
    docker run --rm -p 8180:8180 floci-sidecar-cedar:local
