# mule4-circuit-breaker

A circuit breaker plugin for Mule 4, written from scratch. It opens the circuit when a backend keeps failing, and it can keep that state shared across replicas (for example on CloudHub 2.0), so one replica does not keep hammering a backend the others have already given up on.

> Status: work in progress. The implementation is not here yet.

## Why

Retry on its own turns a slow backend into a failed integration: every caller waits, retries and piles more load on the service that is already struggling. A circuit breaker stops the calls for a while and lets the backend recover. This repository holds the plugin, its tests and a demo with a failing backend, and backs the article on [brunosouzas.com](https://brunosouzas.com).

## Licence

[MIT](LICENSE)
