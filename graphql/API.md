# GraphQL sidecar API

Image `floci/floci-sidecar-graphql`, default port `8181`, contract v1 (see
[docs/contract.md](../docs/contract.md)). Every endpoint is `POST` with a JSON body and answers
JSON. A rejected request is `400 {"error": "...", ...}`; an unexpected failure is `500`.

The sidecar is stateless from the caller's point of view: every schema, query and scalar mapping
travels in the request. It keeps a bounded internal cache of compiled schemas keyed by the SDL
plus the scalar mapping, which produces identical results whether the cache is warm or cold, as
the contract allows.

graphql-java 26.1 is used. No emulator or cloud-provider vocabulary crosses this boundary: a
caller's own custom scalar names are mapped onto generic coercion kinds, and field-level
authorization is entirely the caller's concern (see `denyFields` below).

## `GET /health`

```json
{"status": "ok", "name": "graphql", "version": "0.1.0", "contract": "1"}
```

## `POST /v1/schema/validate`

Parses an SDL document and generates an executable schema from it.

```json
{"sdl": "type Query { hello: String }", "scalars": {"MyDateTime": "date-time"}}
```

`200 {"valid": true}`, or `400 {"error": "...", "issues": [{"category": "PARSER_ERROR"|"VALIDATION_ERROR", "message": "...", "line": 3, "column": 5}]}`.

`scalars` maps each custom scalar name declared in the SDL onto one of this sidecar's coercion
kinds: `date-time`, `date`, `time`, `epoch-seconds`, `email`, `url`, `phone`, `ip-address`,
`json-string`, `boolean`, `long`, `integer`, `short`, `float`, `big-decimal`, `big-integer`,
`base64`. A scalar name with no entry (or mapped to an unknown kind) falls back to identity
pass-through coercion, so schema generation still succeeds.

## `POST /v1/plan`

Statically walks a query's selection set to list every `(typeName, fieldName)` coordinate it
would visit, so a caller can precompute field-level authorization before calling `/v1/execute`.

```json
{"sdl": "...", "scalars": {...}, "query": "{ pet { name } }", "operationName": null}
```

```json
{
  "operationType": "QUERY",
  "fields": [
    {"typeName": "Query", "fieldName": "pet", "directives": [], "typeDirectives": []},
    {"typeName": "Dog", "fieldName": "name", "directives": [], "typeDirectives": []}
  ]
}
```

- An interface or union field is expanded to every concrete candidate type it could resolve to
  (this sidecar does not dispatch resolvers during planning), so a query touching an abstract
  type lists one entry per concrete type, not one entry for the abstract type.
- `directives` are the field's own applied directives; `typeDirectives` are the containing
  object type's, carried alongside so a caller can apply a "field directives, else type
  directives" fallback without a second call. Neither is interpreted by this sidecar.
- A query that fails to parse, or names an operation that does not exist, gets an empty plan
  (`"operationType": null, "fields": []`) rather than an error: `/v1/execute` runs the same query
  through graphql-java's own engine, which reports that case correctly on its own.
- A document with more than one operation and no `operationName` is `400 {"error": "operationName is required when the document defines more than one operation"}`.
  This is the one case planning does not leave to `/v1/execute` to report, since it is a usage
  error of this endpoint's own contract rather than something graphql-java's engine would
  otherwise catch consistently.
- Any other planning failure (for example a fragment cycle) is left uncaught rather than folded
  into an empty plan: refuse the request, never silently report nothing to authorize.

## `POST /v1/execute`

Executes a query.

```json
{
  "sdl": "...",
  "scalars": {...},
  "query": "{ hello }",
  "variables": {},
  "operationName": null,
  "denyFields": [
    {"typeName": "Query", "fieldName": "secret", "errorType": "Unauthorized", "message": "Not authorized"}
  ],
  "resolve": {
    "url": "http://caller/resolve",
    "token": "opaque, per execution",
    "fields": [{"typeName": "Query", "fieldName": "getPost"}],
    "maxBatch": 100
  }
}
```

Response is the GraphQL result envelope: `{"data": ..., "errors": [{"message", "locations", "path", "extensions"}]}`.

- Same ambiguous-operation 400 as `/v1/plan`. Any other syntax or validation problem is left to
  graphql-java's own engine, which reports it as a normal `errors` entry in a `200` response.
- `denyFields` entries are opaque to this sidecar: it nulls out exactly those coordinates with a
  `GraphQLError` carrying `extensions.classification` set to the given `errorType` and the given
  `message`, without ever calling a resolver for them. Both `errorType` and `message` are
  required; there is no default.
- Without `resolve`, every field not covered by `denyFields` keeps graphql-java's default
  property fetcher over a `null` root value, so it resolves to `null`.
- With `resolve`, every coordinate listed in `resolve.fields` (that is not also in `denyFields`,
  which always wins) is answered by a batched callback instead:
  - graphql-java dispatches every resolve-wired coordinate due at one execution level together,
    as one HTTP call to `resolve.url`, split into chunks of at most `resolve.maxBatch` invocations:

    ```http
    POST {resolve.url}
    Authorization: Bearer {resolve.token}
    Content-Type: application/json

    {"invocations": [
      {"id": "0", "typeName": "Query", "fieldName": "getPost",
       "arguments": {"id": "1"}, "source": null, "path": ["getPost"],
       "variables": {}, "selectionSetList": ["id", "title", "author", "author/name"]}
    ]}
    ```
  - `selectionSetList` entries are qualified names (slash-separated for a nested selection), from
    graphql-java's own `SelectedField.getQualifiedName()`.
  - The expected response pairs each `id` with either a value or an error:

    ```json
    {"results": [
      {"id": "0", "data": {"id": "1", "title": "Hello"}},
      {"id": "1", "error": {"message": "Not found", "type": "NotFound", "data": null, "info": null}}
    ]}
    ```
  - A `data` result becomes the field's value, and the `source` of that field's own child
    invocations. An `error` result becomes a `GraphQLError` at that field's path with
    `extensions: {"type", "data", "info"}` copied from the error object.
  - A field the callback did not return a result for, a non-200 response, a malformed response,
    or an unreachable callback all become a field-level error at that call's fields only; they
    never fail fields from a different chunk or a different level.
  - A field whose resolved value is an object containing `__typename` uses it to resolve an
    interface or union type; a value without one falls back to the first candidate concrete type
    (the same placeholder behavior as without `resolve`).
