# mule4-circuit-breaker: project context

## Purpose

A circuit breaker plugin for Mule 4, written from scratch. It opens the circuit when a backend keeps failing on a sliding-window failure rate. State is kept in the Mule runtime's own persistent Object Store, the same mechanism CloudHub 2.0 uses to share data across an app's replicas — so a circuit opened by one replica is visible to the others, with the limits documented below.

## Technology

Stack: Java 17, Mule SDK, Mule 4, MUnit, Maven, GitHub Actions
Delivery: B package
Integration branch: `main`
Data: none
Article: `50-writing/circuit-breaker-in-mulesoft/`
Approver: brunosouzas
Tracker: Linear brunosouzas, team SW, label mulesoft

- Build: `mvn -B verify`
- Test: `mvn -B verify` (JUnit; the MUnit DSL suite is not run by it, see README.md)
- Run locally: not applicable: a plugin; run it through mule4-circuit-breaker-demo-app
- Deliver and verify: a merge into `main` runs `.github/workflows/delivery.yml` (the reusable `mulesoft-library.yml` of github-reusable-workflows, pinned by SHA): the `library / release` job is green, the tag `vX.Y.Z` and its GitHub release exist, and the version is on the Exchange of the trial organisation; the links the articles cite still resolve

## Technology declarations

- `java.version = 17` — [pom.xml](pom.xml).
- `mule.api.version = 1.9.17` — [pom.xml](pom.xml).
- `mule.extensions.api.version = 1.9.18` — [pom.xml](pom.xml).
- `mule.sdk.api.version = 1.2.0` — [pom.xml](pom.xml).
- `mule.extensions.maven.plugin.version = 1.9.6` — [pom.xml](pom.xml).
- `exchange.mule.maven.plugin.version = 0.1.8` — [pom.xml](pom.xml).
- `mule.module.extensions.spring.support.version = 4.9.1` — [pom.xml](pom.xml).
- `mule.version = 4.9.1` — [pom.xml](pom.xml).
- `munit.version = 3.7.1` — [pom.xml](pom.xml).
- `munit.extensions.maven.plugin.version = 1.7.2` — [pom.xml](pom.xml).
- `munit.runtime.version = 4.12.0` — [pom.xml](pom.xml).

These are source declarations, not evidence of installed runtimes. Maven properties may describe build/test dependencies rather than supported runtime minima; unresolved expressions remain inherited until verified.

## Layout and operation sources

Top-level source/documentation directories: `src`.

- [README.md](README.md).
- [pom.xml](pom.xml).
- [.github/workflows/delivery.yml](.github/workflows/delivery.yml).

GitHub default branch inspected on 2026-10-06: `main`. Since 2.0.0 the integration line is `main`; `develop` belongs to the 1.x GitFlow delivery. Build/publish commands mentioned by those sources are context, not authorization.

## Project rules

Before planning, reviewing or changing this project, read [the applicable project rules](rules/README.md).

- Article repository: this repository was made for a published article and keeps what the article published (its code, branches and pipeline). A change here changes the article, so every merge is Bruno's (level 2), also under a delegation.
- OpenSpec: no: every change takes the small path.
- Branches since 2.0.0: a change is a pull request from `feature/sw-<n>-<slug>` into `main`, squash-merged with a message written by hand and no skip-CI marker; merging it releases the plugin. The 1.x tags keep the Azure DevOps GitFlow pipeline the first article series describes; never move or delete them.
