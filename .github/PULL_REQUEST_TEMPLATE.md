## Summary

<!-- What does this PR do? Link any related issues with "Closes #N", including the emulator issue this change serves. -->

## Sidecar

<!-- Which directory: cedar, sidecar-core, or repo-wide. The commit scope must match, since release-please versions each sidecar from the commits that touch it. -->

## Type of change

- [ ] Bug fix (`fix(<sidecar>):`)
- [ ] New feature (`feat(<sidecar>):`)
- [ ] Breaking change of a sidecar's API (`feat(<sidecar>)!:` or `fix(<sidecar>)!:`)
- [ ] Contract change (`docs/contract.md` or `sidecar-core` behaviour every sidecar inherits)
- [ ] Docs / chore / CI

## Contract

<!-- Does the change keep the sidecar free of emulator vocabulary, stateless, and on /v1/*? For a new or changed endpoint: is <sidecar>/API.md updated? -->

## Checklist

- [ ] `./mvnw verify` passes locally
- [ ] `./mvnw -pl <sidecar> -am verify -Dnative` passes (when native configuration, a dependency or a Dockerfile changed)
- [ ] New or updated `@QuarkusTest` in `<sidecar>/src/test` speaking HTTP, covered by the `*IT` subclass
- [ ] `<sidecar>/API.md` updated for any endpoint or payload change
- [ ] Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/) with the sidecar as scope

<!-- First PR here? Your CI checks wait for a maintainer to approve them before they run. That is GitHub's gate on first-time contributors, not a problem with your PR. -->
<!-- Questions, or want feedback on an approach before going further? Join us on Slack: https://join.slack.com/t/floci/shared_invite/zt-3tjn02s3q-A00kEjJ1cZxsg_imTfy6Cw -->
