# Stage 2 DeepSeek Implementation Plan

**Goal:** Add a real DeepSeek adapter and a repeatable end-to-end debugging lesson to the existing Java runtime.

**Architecture:** Preserve the runtime boundary. Add a JSON codec, injectable HTTP transport and deterministic protocol replay. Read the key locally and keep wire JSON separate from authentication.

**Tech Stack:** JDK 21 HttpClient, cached Jackson 2.18.2, Maven metadata for IDE import, executable Java tests and jdb.

## Tasks

- [ ] Define ChatTransport result, codec and adapter contracts; extend ModelReply.Tools and Message.calls to preserve assistant content, retaining old constructors.
- [ ] Write failing protocol tests: request fields/schema/history, tool and final responses, illegal JSON/arguments/finish reasons, HTTP diagnostics, callback snapshots and assistant content preservation.
- [ ] Implement codec and adapter; rerun stage 1 and new behavior tests.
- [ ] Add HTTP transport with official endpoint, no redirects, finite connection/total response time; test against a local server including timeout and reflected-secret error response.
- [ ] Add replay transport, provider CLI, model and question options, opt-in wire body output, and local no-echo key launcher; cover replay and missing-key behavior without live calls.
- [ ] Add project-scoped dependency preparation, IDE Maven metadata and one AllTests entry; no global package installation.
- [ ] Deliver STAGE2.md with exact breakpoints, variables, expected states and exercises; update roadmap status with the live-verification boundary.
- [ ] Run full tests, smoke all three CLI modes without exposing a key, then use jdb to inspect state across the adapter/codec/runtime/tool boundaries.
- [ ] Independent read-only review; address important findings; record verification and commit the stage on its own branch.

Useful commands:

```sh
bash personal-agent/test.sh
bash personal-agent/run.sh --provider replay --messages --wire
bash personal-agent/run-deepseek.sh --debug --messages
```

The last command prompts for the user's real key locally when no environment key is available; it is not used as evidence of a successful live request without a configured key.
