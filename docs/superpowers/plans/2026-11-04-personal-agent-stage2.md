# Stage 2 DeepSeek Implementation Plan

**Goal:** Add a real DeepSeek adapter and a repeatable end-to-end debugging lesson to the existing Java runtime.

**Architecture:** Preserve the runtime boundary. Add a JSON codec, injectable HTTP transport and deterministic protocol replay. Read the key from the ignored project .env and keep wire JSON separate from authentication.

**Tech Stack:** JDK 21 HttpClient, cached Jackson 2.18.2, Maven metadata for IDE import, executable Java tests and jdb.

## Tasks

- [x] Define ChatTransport result, codec and adapter contracts; extend ModelReply.Tools and Message.calls to preserve assistant content, retaining old constructors.
- [x] Write failing protocol tests: request fields/schema/history, tool and final responses, illegal JSON/arguments/finish reasons, HTTP diagnostics, callback snapshots and assistant content preservation.
- [x] Implement codec and adapter; rerun stage 1 and new behavior tests.
- [x] Add HTTP transport with official endpoint, no redirects, finite connection/total response time; test against a local server including timeout and reflected-secret error response.
- [x] Add replay transport, provider CLI, model and question options, opt-in wire body output, and project .env loading; cover replay and missing-key behavior without live calls.
- [x] Add project-scoped dependency preparation, Trae Launch/Attach configurations and IDE Maven metadata and one AllTests entry; no global package installation.
- [x] Deliver STAGE2.md with exact breakpoints, variables, expected states and exercises; update roadmap status with the live-verification boundary.
- [x] Run full tests, smoke all three CLI modes without exposing a key, then use jdb to inspect state across the adapter/codec/runtime/tool boundaries.
- [x] Independent read-only review; address important findings; record verification and commit the stage on its own branch.

Useful commands:

```sh
bash personal-agent/test.sh
bash personal-agent/run.sh --provider replay --messages --wire
bash personal-agent/run-deepseek.sh --debug --messages
```

The last command reads the ignored project .env; it is not evidence of a successful live request until the user fills in a key and runs it.

## Verification (2026-11-04)

- Stage 1 regression: 25 passed. DeepSeek protocol/adapter/CLI: 18 passed. Dotenv: 4 groups passed. Loopback HTTP: 4 passed. Total 51, zero failures.
- Initial adapter/protocol tests failed before implementation. The credential-sanitization regressions and dotenv loading test also failed before their fixes.
- Actual jdb session stopped 9 times across runtime, codec, adapter, argument decoding and filesystem. Inspected runtime history sizes 1 then 3, wire message counts 2 then 4, tool path and successful tool result. Replay completed with exit 0.
- Independent review found two credential disclosure paths; both fixed and independently rechecked: no credential env injection into integratedTerminal; invalid credential and request-header exceptions use fixed diagnostics.
- Trae CN 3.4.14 and Java Debugger 0.60.0 detected locally. Configuration keys checked against the installed extension schema. Direct native app control timed out; graphical F5 launch is unverified.
- Project .env created with empty key, mode 0600; git check-ignore confirms it is ignored. Only .env.example is eligible for commit. Existing process environment overrides file values.
- Maven metadata is provided for IDE import; no global Maven installation or Maven build was performed.
- Real DeepSeek requests remain unverified until the user fills the local key. No credentials were searched in other apps or sent externally.
