# Contributing to floci-sidecars

Thank you for your interest in contributing! Sidecars are the engines a local cloud emulator
cannot ship inside its own image, and they are meant to be small enough that one afternoon is
enough to fix a bug or add an endpoint.

## Ways to Contribute

- **Bug reports**: open an issue with the sidecar name, the image tag (`docker inspect` shows
  `io.floci.sidecar.version`), the request you sent and the response you got
- **Feature requests**: open an issue describing the generic question the sidecar should answer
  (keep the [scope rules](#scope) in mind)
- **Pull requests**: bug fixes, new endpoints, a new sidecar, or improvements to `sidecar-core`

## Getting Started

### Prerequisites

- Java 25+ (the Maven wrapper downloads Maven itself)
- Docker, to build and run an image

If you need to install a JDK, [SDKMAN](https://sdkman.io/) is a convenient option:

```bash
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk install java 25-tem
```

### Build & Test

```bash
git clone https://github.com/floci-io/floci-sidecars.git
cd floci-sidecars

./mvnw verify                              # every module
./mvnw -pl cedar -am verify                # one sidecar and what it depends on
./mvnw -pl cedar -am test -Dtest=CedarSidecarTest#authorizeLetsForbidOverridePermit
```

### Try it as an image

Always build from the repository root; a sidecar's Dockerfile copies `sidecar-core` too.

```bash
docker build -f cedar/Dockerfile --build-arg SIDECAR_VERSION=0.0.0-local -t floci-sidecar-cedar:local .
docker run --rm -p 8180:8180 floci-sidecar-cedar:local
curl -s localhost:8180/health
```

To run Floci against your local build, point it at the image or at the running container:

```bash
FLOCI_SERVICES_VERIFIEDPERMISSIONS_CEDAR_IMAGE=floci-sidecar-cedar:local
FLOCI_SERVICES_VERIFIEDPERMISSIONS_CEDAR_URL=http://host.docker.internal:8180
```

## Architecture

See [AGENTS.md](AGENTS.md) for the layout and the rules, and [docs/contract.md](docs/contract.md)
for the contract every sidecar implements. `AGENTS.md` is the canonical agent instructions file
for this repository, following the [AGENTS.md standard](https://agents.md/). If your coding agent
expects a different filename, create a local symlink instead of copying the file:

```bash
ln -s AGENTS.md CLAUDE.md
ln -s AGENTS.md GEMINI.md
```

### Rules that trip up most PRs

- **No emulator vocabulary across the HTTP boundary.** A sidecar answers a generic question
  (is this authorized, run this query). AWS, GCP or Azure service names, error codes and field
  names belong to the emulator that calls it.
- **Register only `/v1/*` handlers.** `PORT`, `/health`, JSON parsing and the 400/405/500
  envelope come from `SidecarServer` in `sidecar-core`. Do not add a second health endpoint or
  parse the body yourself.
- **A rejected request is `IllegalArgumentException`** (or a type registered with
  `badRequestOn`), which becomes `400`. Anything else is `500` and gets logged.
- **Stateless.** Everything a request needs travels in the request. Nothing written to disk may
  be relied on to survive.
- **Tests start the sidecar in-process on port 0 and speak HTTP.** That is the surface the
  emulator uses, so it is the surface that is tested. `./mvnw verify` must pass, and the Docker
  build must succeed when the Dockerfile or `.dockerignore` changed.
- **Style follows [floci-io/floci](https://github.com/floci-io/floci)**: no `var`, explicit
  imports, braces always, JBoss logging in a `LOG` field, no em-dashes anywhere.

## Adding a sidecar

Follow the steps in the [README](README.md#adding-a-sidecar). In short: copy `cedar/` to
`<name>/`, register the module in the root `pom.xml`, the paths in `.dockerignore`, the package in
`release-please-config.json` and `.release-please-manifest.json` (start at `0.1.0`), and the
directory in the `ci.yml` image matrix. Document the endpoints in `<name>/API.md` and add a row
to the README image table. Open the pull request before the emulator-side change that will
consume it: the sidecar releases first, the consumer pins the released tag.

## Scope

This repository holds engines that an emulator cannot ship in its own image: heavy dependencies,
native runtimes, other language ecosystems. Out of scope (PRs in these areas will be declined):

- Emulator behaviour: request shapes, error codes and service semantics live in the emulator
- A sidecar that wraps a stock upstream image with no code of its own; the emulator runs that
  image directly
- A sidecar that needs the Docker socket, a volume or a host path

## Commit Message Format

This project uses [Conventional Commits](https://www.conventionalcommits.org/). PR titles should
follow the same format, since they become the squash-merge commit message. **The scope is the
sidecar directory**: release-please derives each sidecar's version from the commits that touch
its directory, and the changelog entry is the commit description.

```
<type>(<scope>): <description>
```

- **type**: lowercase, from the table below
- **scope**: the sidecar directory (`cedar`), `core` for `sidecar-core`, or none for repo-wide
  changes
- **description**: imperative mood, no trailing period
- Append `!` before the colon for a breaking change of a sidecar's API: `feat(cedar)!:`

| Type | When to use |
|------|-------------|
| `feat` | New endpoint, new capability, new sidecar |
| `fix` | Bug fix |
| `perf` | Performance improvement |
| `docs` | Documentation only |
| `refactor` | Code restructure without behavior change |
| `test` | Adding or updating tests |
| `build` / `ci` | Build system or CI workflow changes |
| `chore` | Dependencies, housekeeping, releases |

Examples:

```
feat(cedar): return the schema namespaces from /v1/schema/validate
fix(core): treat an empty request body as a bad request
ci: pin release-please-action to a commit
```

Do not include `Co-Authored-By` trailers for AI tools in commit messages. Attribution should be
limited to human contributors.

## Changelog

Each sidecar's `CHANGELOG.md` is generated by
[release-please](https://github.com/googleapis/release-please) from Conventional Commit messages.
**Do not edit it by hand.** Your commit message *is* the changelog entry, so write it from the
consumer's point of view (what was broken, what is new), not how it was implemented.

## Pull Request Guidelines

1. Branch off `main`: `git checkout -b feat/cedar-my-change`
2. Open a PR targeting `main`
3. CI runs the Maven reactor and builds every sidecar image without pushing. All checks must pass
   before merge
4. Keep PRs focused: one sidecar, one feature or fix per PR. A `sidecar-core` change that touches
   every sidecar is the one exception
5. Update `<name>/API.md` in the same PR if you add or change an endpoint or a payload
6. Reference related issues in the PR description, including the emulator issue the change serves

### Testing policy

- PRs that introduce new behavior must include tests validating that behavior
- Bug-fix PRs should include a regression test whenever the bug can be covered realistically
- Docs, formatting, and low-risk internal refactors may not need new tests, but the existing suite
  must pass
- If a PR includes no new tests, explain why in the description

## Release Process (maintainers)

release-please keeps one release pull request open per sidecar. Merging it bumps
`<name>/version.txt`, tags `<name>-vX.Y.Z`, and the `Release` workflow publishes
`floci/floci-sidecar-<name>:X.Y.Z` and `:latest` to Docker Hub for `linux/amd64`
and `linux/arm64`. Nothing is published on an ordinary merge.

A prerelease is published from any branch with the `Release` workflow's manual trigger
(Actions, Release, Run workflow) by naming the sidecar and a tag such as `1.1.0-rc.1`. It never
moves `latest`. Use it when an emulator pull request needs a sidecar change that has not been
released yet: publish the rc, pin it on the emulator side, and re-pin to the final tag once the
release PR is merged.

## Reporting Security Issues

Please do **not** open public issues for security vulnerabilities. Report them privately using
[GitHub private vulnerability reporting](https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing/privately-reporting-a-security-vulnerability).

## License

By contributing, you agree that your contributions will be licensed under the [MIT License](LICENSE).
