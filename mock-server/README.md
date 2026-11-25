# testPike mock-server

Contract-driven API simulator. Mock spaces are built from your OpenAPI specs and
served with deterministic matching on the authored response examples; unmatched
traffic can optionally fall back to AI generation (honestly labeled, inspectable).

## Two ports, one process

| Port (default) | Purpose |
| --- | --- |
| `18085` (`MOCK_SERVER_MANAGEMENT_PORT`) | management plane (actuator health/info) |
| `18086` (`MOCK_SERVER_TRAFFIC_PORT`) | traffic plane, serves `/mocks/**` |

The service never touches PostgreSQL directly: rules arrive only as a polled,
version-pinned snapshot from the core service (`CORE_BASE_URL`, polled every 3s
for the space named by `MOCK_SERVER_LOAD_SPACE_KEY`; a blank key = fail-closed,
no serving).

## Running it

The installer manages this service as `tp-mock-server` (see `INSTALL.md`).
No manual start is needed in a standard install.

## Recommended first import

No API spec at hand? Import the bundled sample and see the whole flow end to end:

```
examples/petstore-mini.yaml
```

It defines 4 endpoints (`GET/POST /pets`, `GET/DELETE /pets/{petId}`) with
authored response examples.

1. Import the spec: API testing workbench → spec import → upload the YAML.
2. Create the space: top bar **API Mocking → Mock Spaces** → *New Space*
   (name + key are required; pick auth/base-path modes as needed), then publish it.
   The space page lets you copy its access URL and a ready curl example.
3. Verify deterministic matching:

```bash
curl http://localhost:18086/mocks/<spaceKey>/pets
# -> [{"id":1,"name":"Tommy","tag":"dog"},{"id":2,"name":"Kitty","tag":"cat"}]

curl http://localhost:18086/mocks/<spaceKey>/pets/1
# -> {"id":1,"name":"Tommy","tag":"dog"}
```

If the reply matches the authored example above, the space is live.
