Guidance for AI coding agents working in the Floci Sidecars repository.

## What this repository is

One directory per sidecar, plus `sidecar-core`, the shared Quarkus providers. A sidecar is a
stateless HTTP service that an emulator starts as a container. It must implement
`docs/contract.md` and must not know which emulator is calling it. Every sidecar is a Quarkus
application compiled to a GraalVM native executable; `sidecar-core` is a plain library jar.

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
  only what `.dockerignore` allows. When you add a sidecar, add its paths there. `Dockerfile` is
  the local Mandrel build; `Dockerfile.package` only packages a CI-built executable from
  `native/<arch>/`. Both must stay identical below the build stage.
- **Native-image configuration belongs to the sidecar that needs it.** Reflection registrations
  (`@RegisterForReflection(classNames = ...)`), `META-INF/native-image/**/jni-config.json` for a
  library's JNI callbacks, and `quarkus.native.additional-build-args` such as
  `--initialize-at-run-time=<package>` for any library that loads a native runtime from a static
  initializer. Never widen these blindly: derive them from the library's sources or the native
  library's string table, and prove them with the `*IT` tests under `-Dnative`.
- **A native library ships as one file per architecture**, unpacked from its jar at
  `prepare-package` and handed to the process by an environment variable the image sets. Never
  embed the jar's other platform builds in the executable (`quarkus.native.resources.excludes`).
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
- A sidecar's tests are `@QuarkusTest` classes speaking HTTP through RestAssured; that is the
  same surface the emulator uses. Each has an `*IT` subclass annotated `@QuarkusIntegrationTest`
  that runs the same tests against the packaged executable under `-Dnative`.
- `./mvnw verify` must pass before a commit, and `./mvnw -pl <name> -am verify -Dnative` when
  anything touching native configuration, dependencies or the Dockerfiles changed.

## Build & run

    ./mvnw verify
    ./mvnw -pl cedar -am verify -Dnative
    docker build -f cedar/Dockerfile --build-arg SIDECAR_VERSION=0.0.0-local -t floci-sidecar-cedar:local .
    docker run --rm -p 8180:8180 floci-sidecar-cedar:local
