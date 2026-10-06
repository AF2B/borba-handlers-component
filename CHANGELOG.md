# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [2.0.0] - 2026-10-06

### Added

- `request-id`, which gives each request an id, from the `X-Request-Id` header when it is safe to log and a UUID otherwise, and
  puts it in the response and in every error body and log line.
- `access-log`, which logs the method, the path (never the query string), the status and the time of each request.
- `borba.handlers.json`, a strict JSON reader that refuses text after the value and an object with a key twice, which Jackson
  accepts by default, and a writer.
- `borba.handlers.errors`, which turns an exception into a response: the status, code and message an author gave to an error
  that carries a `:status`, and a 500 that says nothing about anything else.
- `body-parser`, a body parser with a limit, checked against the declared length and against what is sent. `:max-body-bytes`
  sets it for the component; the default is 1 MiB.
- `:borba/not-found`, the reserved handler of a request that no route matches.
- `:request-id` and `:request` in what a handler receives.
- A query string that cannot be decoded is a 400 with the code `invalid-query`.
- The start fails naming the handler when its function is not one, when it asks for an interceptor that is not registered, and
  when a handler is registered under a reserved key; a handler that does not return a response map is a logged 500.
- A test suite with 100% of the lines covered, including a round trip of generated JSON.

### Changed

- **Breaking:** the value of `:service/handlers` is a map from the handler key to `{:chain [...] :handler interceptor}`, where
  it was a vector of interceptors with the handler last.
- **Breaking:** the interceptors a handler asks for with `handler-interceptors` are looked up in `:interceptors`, the map of
  `borba-interceptors-component`. `borba.handlers.registry/interceptor` is gone.
- **Breaking:** the body of an error is `{:error code :message "..." :request-id "..."}`. `:message` used to be the message of the
  exception, which Pedestal prefixes with the interceptor and the class that threw it.
- **Breaking:** JSON is read strictly, with keyword keys, and written with jsonista. Cheshire is no longer a dependency.
- **Breaking:** a request body is limited to 1 MiB by default, and a body that has content must be `application/json` or a
  `+json` type.
- **Breaking:** moves to Pedestal 0.8.2 and Integrant 1.0, where a reference must be a qualified keyword.
- `:query-params` is read from the query string, where it used to be copied from `:params`, and a parameter without a value is
  an empty string.
- The component logs through `tools.logging` instead of printing.
- The published library is named `io.github.af2b/borba-handlers-component`, and it no longer depends on Jetty or on a logging
  backend: the server brings the first, the service chooses the second.

### Fixed

- `parse-body` read only a `BufferedReader` and turned any other body into the text of its object, and Jetty hands over an
  `InputStream`.

### Security

- Pins Jackson to 2.22.3. The 2.22.2 that jsonista 1.0.1 brings has four high advisories (GHSA-7hhh-6rmp-j9qf,
  GHSA-p6pp-m3f8-5c89, GHSA-cxp5-3px4-pw24 and GHSA-wv8q-qhhj-9h54), which the dependency scan of the pipeline reported.

## [1.0.0] - 2026-03-29

First release: the `handler`, `interceptor` and `handler-interceptors` registries, the `:service/handlers` Integrant component
and the interceptors that parse the request and write the response as JSON.

[Unreleased]: https://github.com/AF2B/borba-handlers-component/compare/v2.0.0...HEAD
[2.0.0]: https://github.com/AF2B/borba-handlers-component/compare/v1.0.0...v2.0.0
[1.0.0]: https://github.com/AF2B/borba-handlers-component/releases/tag/v1.0.0
