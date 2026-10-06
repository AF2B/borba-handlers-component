# borba-handlers-component

[![CI](https://github.com/AF2B/borba-handlers-component/actions/workflows/ci.yml/badge.svg)](https://github.com/AF2B/borba-handlers-component/actions/workflows/ci.yml)

The request layer of a Borba service. A handler is a function registered under a keyword; an [Integrant](https://github.com/weavejester/integrant)
component builds the [Pedestal](https://pedestal.io) chain around each one. The chain gives every request an id, reads a strict
JSON body within a limit, and turns every failure into a typed error whose body says nothing about the server.

## Install

```clojure
io.github.af2b/borba-handlers-component
{:git/url "https://github.com/AF2B/borba-handlers-component"
 :git/tag "v2.0.0"
 :git/sha "<the commit of the tag, printed in the release notes>"}
```

It depends on Clojure, Integrant, `tools.logging`, [jsonista](https://github.com/metosin/jsonista) and Pedestal's routing module.
The server itself comes from `borba-server-component`, which is where Jetty is.

## Use

Register a handler with a `defmethod`. The dispatch value is the keyword the routes refer to it by. The method receives the
components of the service and returns the function that handles the requests:

```clojure
(require '[borba.handlers.registry :as registry])

(defmethod registry/handler :things/create
  [_ {:keys [db]}]
  (fn [{:keys [body-params query-params request-id]}]
    {:status 201
     :body   {:received body-params :query query-params :id request-id}}))
```

The function receives one map and returns a response map. A `:body` that is a map or a collection is written as JSON.

| Key | What it is |
|---|---|
| `:components` | The components of the service |
| `:body-params` | The JSON body as data with keyword keys; `{}` without a body or with a JSON `null` |
| `:query-params` | The query string, as a map with keyword keys |
| `:path-params` | The parameters of the path |
| `:header-params` | The headers, with lower-case string keys |
| `:request-id` | The id of the request, also in the logs and in the response |
| `:request` | The Ring request, for what the rest does not cover |

The component builds the chains:

```clojure
{:service/namespaces [com.example.things.handlers]

 :ig/system
 {:service/handlers
  {:components     #ig/ref :service/components
   :interceptors   #ig/ref :service/interceptors
   :max-body-bytes 1048576}}}
```

Its value is a map from the handler key to its `:chain`, the interceptors that run before the handler, and its `:handler`, the
interceptor that calls it. `borba-routes-component` puts the interceptors of a route between the two and builds the route table.
The handlers are found by the `defmethod`s that have been loaded, so the namespaces that register them go under
`:service/namespaces`, which `borba-core-component` loads before the system starts.

| Option | What it is | Default |
|---|---|---|
| `:components` | The components of the service, handed to every handler | none |
| `:interceptors` | The map that `borba-interceptors-component` builds, where the interceptors a handler asks for are looked up | none |
| `:max-body-bytes` | The largest request body, in bytes | 1048576 (1 MiB) |

### Interceptors for one handler

A handler can ask for more interceptors, which run after the ones every handler has. They are named by the keywords they are
registered under in `borba-interceptors-component`:

```clojure
(defmethod registry/handler-interceptors :admin/dashboard
  [_]
  [:auth/admin :audit/log-access])
```

A keyword that is not in `:interceptors` fails the start, naming the handler and the keyword.

## The chain

Every handler runs these, in this order:

| Interceptor | What it does |
|---|---|
| `request-id` | Gives the request an id and puts it in the `X-Request-Id` header of the response |
| `access-log` | Logs the method, the path, the status and the time of each request |
| `http-error-handler` | Turns an exception into a typed response that leaks nothing |
| `inject-components` | Puts the components of the service in the request |
| `parse-query` | Reads the query string |
| `parse-path-params`, `parse-headers` | Make sure `:path-params` is there, and copy `:headers` to `:headers-map` |
| `parse-body` | Reads a strict JSON body within a limit |
| `json-response` | Writes a map or collection body as JSON on the way out |

The request id and the error handler come first so that everything after them, and every failure, has an id and a response of
the same shape.

### Request ids

The id is the one the client sent in `X-Request-Id`, when it is safe to write to a log (letters, digits, dot, underscore and
hyphen, up to 128 characters), and a new UUID otherwise. A header with a newline in it cannot forge a line of the log.

### The body

```
POST /things?page=2        {"name":"Ana"}
=> 201 {"received":{"name":"Ana"},"query":{"page":"2"},"id":"client-1"}      (X-Request-Id: client-1)

POST /things               {nope
=> 400 {"error":"invalid-json","message":"The request body is not valid JSON.","request-id":"945a13a1-..."}

POST /things               {"a":1,"a":2}
=> 400 {"error":"invalid-json", ...}

POST /things               (a body of 100 bytes, with a limit of 64)
=> 413 {"error":"payload-too-large","message":"The request body is larger than 64 bytes.", ...}

POST /things               hello        (Content-Type: text/plain)
=> 415 {"error":"unsupported-media-type","message":"The request body must be application/json.", ...}
```

The body is read strictly. By default Jackson ignores what follows the first value of a document and keeps the last of two equal
keys of an object, which is how two parsers come to read one request in two ways. Here both are a 400. The limit is checked
against the length the request declares, before reading anything, and against what it sends, one byte past the limit, so a
client that lies about its length does not get through. A `Content-Type` of `application/json`, or of any `+json` type, is
required when there is a body.

A parameter that is repeated in the query string is a vector (`?a=1&a=2` is `{:a ["1" "2"]}`), one without a value is an empty
string (`?flag` is `{:flag ""}`), and one that cannot be decoded is a 400.

## Failures

The body of an error is a map with the code, a message and the request id, the same convention as `borba.railway`: the code is
what a client matches on.

```json
{"error": "invalid-json", "message": "The request body is not valid JSON.", "request-id": "945a13a1-6c0e-4c97-a3f4-c345786865b1"}
```

An exception becomes a response in one of two ways:

- **An error its author meant for the client** carries an HTTP `:status` in its data, such as
  `(ex-info "Token expired" {:status 401 :error :token-expired})`. The client gets that status, the code in `:error` (or the
  code of the status) and, for a client error, its message. `:details` in the data is passed on for a client error. The message
  of a 5xx is never passed on.
- **Anything else** is a bug or a broken dependency. The client gets a 500 with `internal-error` and a message that says
  nothing about it. The exception, with its stack trace and the request id, goes to the log.

```
GET /crash                 (the handler throws IllegalStateException "password=hunter2")
=> 500 {"error":"internal-error","message":"An internal error occurred.","request-id":"3e76aa64-..."}
log:  ERROR borba.handlers.interceptors - request 3e76aa64-... failed
      java.lang.IllegalStateException: password=hunter2
```

A handler must return a map with an integer `:status`. A handler that returns `nil`, or a vector, is a 500 that is logged, instead
of a client left waiting or given a 404.

Failures that are part of the domain are better returned as data, with `borba.railway/failure` and
`borba.railway.http/railway->response`, which use the same convention.

The codes of the statuses that have one: 400 `bad-request`, 401 `unauthorized`, 403 `forbidden`, 404 `not-found`,
405 `method-not-allowed`, 409 `conflict`, 413 `payload-too-large`, 415 `unsupported-media-type`, 422 `validation-failed`,
429 `too-many-requests`, 500 `internal-error`, 502 `bad-gateway`, 503 `service-unavailable` and 504 `gateway-timeout`.

### What no route matches

`:borba/not-found` is a handler of its own, reserved, with a short chain that reads no body. `borba-routes-component` sends the
requests that no route matches to it, so they get the same typed 404, with a request id, as any other failure.

## API

| Name | What it does |
|---|---|
| `borba.handlers.registry/handler` | Registers the handler of a keyword |
| `borba.handlers.registry/handler-interceptors` | Names the interceptors a handler asks for |
| `borba.handlers.component/build` | Builds the chains of every registered handler |
| `:service/handlers` | The Integrant key that does it |
| `borba.handlers.interceptors` | The interceptors of the chain, and `body-parser` to set the limit of one |
| `borba.handlers.errors/describe`, `response` | How an exception becomes a response |
| `borba.handlers.json/read-json`, `write-json` | The strict reader and the writer |

## Design notes

- **Nothing about the server reaches the client.** The message of an exception names the interceptor and the classes involved,
  and often a query or an address. It goes to the log with the request id, which is what links the two.
- **The body is bounded twice.** What a request declares is checked before it is read, and what it sends is checked as it is
  read, so neither a large `Content-Length` nor a lie about it makes the service buffer a large body.
- **Strict JSON is a security property.** A request that two parsers read differently can pass a check in one and be acted on
  by the other.
- **A handler is a function of data.** It gets a map and returns a map, so it is tested without a server.

## Development

```bash
make check      # lint, format, conventions, reflection, tests, coverage
make ci         # everything the pipelines enforce
```

See [CONTRIBUTING.md](CONTRIBUTING.md). The repository follows the [Borba standard](https://github.com/AF2B/borba-tooling/blob/main/docs/standard.md).

## License

[MIT](LICENSE)
