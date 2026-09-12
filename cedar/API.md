# Cedar sidecar API

Image `floci/floci-sidecar-cedar`, default port `8180`, contract v1 (see
[docs/contract.md](../docs/contract.md)). Every endpoint is `POST` with a JSON body and answers
JSON. A rejected request is `400 {"error": "..."}`; an unexpected failure is `500`.

The sidecar is stateless. Policies, templates, entities and context travel in each request, so
isolation between callers is the caller's concern, not the sidecar's.

Cedar Java 4.10.0 is used, which preserves Cedar 4 semantics: a matching `forbid` overrides
matching `permit` policies.

## `GET /health`

```json
{"status": "ok", "name": "cedar", "version": "1.0.0", "contract": "1"}
```

## `POST /v1/entity-type/validate`

Checks that a string is a well-formed Cedar entity type name.

```json
{"entityType": "PhotoApp::User"}
```

`200 {"valid": true}` or `400` naming the invalid type.

## `POST /v1/schema/validate`

Parses a schema in Cedar JSON schema format.

```json
{"schema": "{\"PhotoApp\":{\"entityTypes\":{},\"actions\":{}}}"}
```

`200 {"valid": true}` or `400` with the parse error.

## `POST /v1/policy/parse`

Parses one static policy or one policy template and returns its effect and AST.

```json
{"statement": "permit(principal, action, resource);", "template": false}
```

```json
{"effect": "Permit", "ast": {"effect": "permit", "principal": {"op": "All"}, "...": "..."}}
```

`effect` is `Permit` or `Forbid`. With `"template": true` the statement may use `?principal`
and `?resource` slots and `ast` is the template's AST.

## `POST /v1/policy/validate`

Validates one policy or template against a schema in STRICT mode.

```json
{
  "schema": "{\"PhotoApp\":{...}}",
  "statement": "permit(principal == PhotoApp::User::\"alice\", action, resource);",
  "template": false
}
```

`200 {"valid": true}` or `400` whose message starts with
`The Cedar policy failed STRICT schema validation`.

## `POST /v1/authorize`

Evaluates one authorization request against a set of stored policies and templates.

```json
{
  "request": {
    "principal": {"entityType": "PhotoApp::User", "entityId": "alice"},
    "action": {"actionType": "PhotoApp::Action", "actionId": "view"},
    "resource": {"entityType": "PhotoApp::Photo", "entityId": "vacation.jpg"},
    "entities": {"entityList": [
      {"identifier": {"entityType": "PhotoApp::User", "entityId": "alice"},
       "attributes": {"age": {"long": 30}},
       "parents": [{"entityType": "PhotoApp::Group", "entityId": "friends"}],
       "tags": {}}
    ]},
    "context": {"contextMap": {"authenticated": {"boolean": true}}}
  },
  "policies": [
    {"policyId": "p1", "policyType": "STATIC",
     "statement": "permit(principal, action, resource);"},
    {"policyId": "p2", "policyType": "TEMPLATE_LINKED", "policyTemplateId": "t1",
     "principal": {"entityType": "PhotoApp::User", "entityId": "alice"},
     "resource": {"entityType": "PhotoApp::Photo", "entityId": "vacation.jpg"}}
  ],
  "templates": {
    "t1": {"statement": "forbid(principal == ?principal, action, resource == ?resource);"}
  }
}
```

```json
{"decision": "DENY", "determiningPolicyIds": ["p2"], "errors": []}
```

- `entities` is either `{"cedarJson": "<Cedar entities JSON array as a string>"}` or
  `{"entityList": [...]}` as above. In an entity list the last definition of a duplicate
  identifier wins. Attribute and tag values are single-member unions: `boolean`, `long`,
  `string`, `entityIdentifier`, `ipaddr`, `decimal`, `datetime`, `duration`, `set`, `record`.
- `context` is either `{"cedarJson": "<Cedar context JSON object as a string>"}` or
  `{"contextMap": {...}}` with the same union values.
- Entity identifiers accept `entityType`/`entityId` or `type`/`id`.
- `errors` lists evaluation errors Cedar reported while reaching the decision.
