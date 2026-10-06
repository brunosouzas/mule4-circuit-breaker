# mule4-circuit-breaker: project context

## Purpose

A circuit breaker plugin for Mule 4, written from scratch. It opens the circuit when a backend keeps failing on a sliding-window failure rate. State is kept in the Mule runtime's own persistent Object Store, the same mechanism CloudHub 2.0 uses to share data across an app's replicas — so a circuit opened by one replica is visible to the others, with the limits documented below.

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
- [.github/workflows/build.yml](.github/workflows/build.yml).
- [azure-pipelines.yml](azure-pipelines.yml).

GitHub default branch inspected on 2026-10-06: `main`. This context is prepared against develop, the integration line selected from the project pipeline/release sources. The GitHub default is separately main. Build/publish commands mentioned by those sources are context, not authorization.

## Project rules

Before planning, reviewing or changing this project, read [the applicable project rules](rules/README.md).
