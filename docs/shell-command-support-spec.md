# Shell Command Support Spec

## Summary

This document specifies the minimum changes needed for `codex-android` to support shell commands and file edits in a workspace-backed Codex session.

Today, the Android client sends `sandbox = "workspace-write"` but also hardcodes `approvalPolicy = "never"` for both `thread/start` and `thread/resume`. In observed sessions, the effective session policy still becomes `sandbox_policy = read-only` and `approval_policy = never`, which prevents shell commands from running and blocks file writes. App changes are necessary, but not sufficient, if the downstream app-server or bridge continues to override the requested policy.

## Problem Statement

The current mobile app cannot reliably create a Codex session that can:

- execute shell commands
- edit files in the current workspace
- surface or respond to approval requests in a controlled way

Known current behavior:

- The client requests `sandbox = "workspace-write"`.
- The client defaults `approvalPolicy = "never"`.
- The client already auto-accepts `item/commandExecution/requestApproval` and `item/fileChange/requestApproval`.
- Recorded sessions created through the app-connected path still run with `read-only` sandboxing and `never` approval.

This means the app has two gaps:

1. It requests an approval mode that is incompatible with command execution.
2. It does not expose a first-class permissions model, so behavior is implicit and difficult to debug.

There is also one likely downstream gap:

1. The app-server or VS Code bridge may be rewriting requested permissions to `read-only` / `never` for external clients.

## Goals

- Allow shell commands and file changes inside the active workspace.
- Make session permissions explicit in app code and app UI state.
- Support a safe default that does not require a full approval UI on day one.
- Make failures diagnosable when the downstream server ignores requested permissions.

## Non-Goals

- Full dangerous local access outside the workspace.
- Full per-command approval UX in the first iteration.
- Changes to Codex CLI core behavior.

## Required Effective Session Policy

To allow normal shell use inside the repo, the effective session policy must be equivalent to:

- `sandbox_mode = "workspace-write"`
- `approval_policy = "on-request"` or `approval_policy = "on-failure"`

For the JSON-RPC app-server path, this maps to:

- request field `sandbox = "workspace-write"` on `thread/start` and `thread/resume`
- request field `approvalPolicy = "on-request"` or `approvalPolicy = "on-failure"`

If the bridge uses structured permissions objects internally, the equivalent must be:

- `sandboxPolicy.type = "workspaceWrite"`
- `approvalPolicy = "on-request"` or `approvalPolicy = "on-failure"`

## Current Code Impacted

- `app/src/main/java/com/local/codexmobile/data/JsonRpcModels.kt`
  - `ThreadStartParams`
  - `ThreadResumeParams`
- `app/src/main/java/com/local/codexmobile/data/CodexAppServerClient.kt`
  - auto-approval handling for command and file change requests
- connection or settings flow that chooses how new threads are started

## Proposed Design

### 1. Introduce an explicit session permissions model

Add an app-side model for session permissions instead of hardcoding literals in request DTOs.

Suggested model:

```kotlin
enum class ApprovalPolicy {
    NEVER,
    ON_REQUEST,
    ON_FAILURE
}

enum class SandboxMode {
    READ_ONLY,
    WORKSPACE_WRITE
}

data class SessionPermissions(
    val approvalPolicy: ApprovalPolicy = ApprovalPolicy.ON_REQUEST,
    val sandboxMode: SandboxMode = SandboxMode.WORKSPACE_WRITE,
    val autoApproveWorkspaceActions: Boolean = true
)
```

This model should be the single source of truth for thread creation and resume.

### 2. Change thread start and resume defaults

Replace the current hardcoded defaults:

- `approvalPolicy = "never"`
- `sandbox = "workspace-write"`

with:

- `approvalPolicy = "on-request"`
- `sandbox = "workspace-write"`

`on-request` is the better first default because the app already auto-accepts approval requests and because it matches the expected workflow for commands that may require permission.

`on-failure` is an acceptable fallback if the server behaves better with that mode, but `never` must not be used for shell-enabled sessions.

### 3. Split session modes in the app

Support two explicit modes:

- `Safe`
  - `approvalPolicy = "never"`
  - `sandbox = "read-only"`
- `Workspace`
  - `approvalPolicy = "on-request"`
  - `sandbox = "workspace-write"`

The mode must be visible before starting a thread and stored with the active connection or thread metadata.

This makes it obvious whether a failure is expected policy behavior or a bug.

### 4. Keep auto-approval behind a clear policy gate

The current auto-accept handler is useful for a first iteration, but it must be conditioned on the selected session mode.

Required behavior:

- In `Safe` mode, do not auto-approve command or file-change requests.
- In `Workspace` mode, auto-approve command and file-change requests that stay inside the workspace.
- Reject or ignore requests that target paths outside the workspace until a real approval UI exists.

If the request payload contains insufficient path or command detail to make that distinction safely, log the event and fall back to explicit rejection rather than unconditional accept.

### 5. Add session policy verification after thread creation

After `thread/start` or `thread/resume`, the app should verify the effective session policy from the first available server/session metadata event if available.

If the effective policy is downgraded to:

- `approval_policy = never`
- `sandbox_policy = read-only`

then the app should:

- mark the session as `restricted by server`
- disable shell affordances in the UI
- show a precise reason such as `Server created a read-only session; requested workspace-write`

This is important because app-side fixes alone may not override a stricter bridge policy.

### 6. Add structured diagnostics for permission mismatches

When starting or resuming a thread, log:

- requested approval policy
- requested sandbox mode
- workspace cwd
- effective approval policy if reported
- effective sandbox policy if reported

This should be visible in app logs and optionally in a debug panel.

## Protocol Changes

No protocol extension is required for the minimum implementation if the existing app-server already accepts:

- `thread/start { cwd, approvalPolicy, sandbox }`
- `thread/resume { threadId, cwd, approvalPolicy, sandbox }`

Required app behavior:

- always send both fields explicitly
- never rely on server defaults for shell-enabled sessions

Optional protocol improvement:

- expose effective permissions in the `thread/start` and `thread/resume` result payload

That would remove the need to infer policy from later events.

## UI Changes

Minimum UI:

- Add a session mode toggle with `Safe` and `Workspace`.
- Show the chosen mode in the connection or composer header.
- Show a warning banner when the server returns or implies a stricter policy than requested.

Deferred UI:

- per-command approval prompts
- per-workspace trust management
- persistent permission presets

## Security Constraints

The first iteration must enforce these limits:

- only `workspace-write` is supported
- no `danger-full-access`
- no auto-approval for actions outside the selected workspace
- if workspace root is unknown, fall back to `Safe` mode

## Rollout Plan

### Phase 1: App-only minimum viable support

- add `SessionPermissions`
- change defaults to `on-request` + `workspace-write`
- gate auto-approval by session mode
- add logging and visible session mode

Expected result:

- shell commands work if the downstream app-server honors requested permissions

### Phase 2: Detect and surface server-side downgrades

- inspect thread/session metadata
- mark sessions as restricted when effective policy does not match requested policy
- show precise error state in UI

Expected result:

- users can distinguish app bugs from server policy overrides

### Phase 3: Full approval UX

- replace unconditional auto-accept with explicit approval prompts
- allow user review of command requests and file edits

Expected result:

- safer permission model without depending on silent auto-approval

## Acceptance Criteria

A build is acceptable when all of the following are true:

1. Starting a new thread in `Workspace` mode sends `approvalPolicy = "on-request"` and `sandbox = "workspace-write"`.
2. Resuming a thread in `Workspace` mode sends the same permission fields explicitly.
3. The app can execute a harmless shell command in a workspace-backed session when the server honors the requested policy.
4. The app can create or edit a file inside the workspace when the server honors the requested policy.
5. If the server forces `read-only` or `never`, the app shows that the session was restricted by the server.
6. `Safe` mode continues to create read-only sessions without shell access.

## Open Questions

- Does the app-server currently expose effective session permissions directly, or only indirectly through events and logs?
- Is the `read-only` downgrade happening in the app-server itself or in the VS Code extension bridge that fronts it?
- Does `approvalPolicy = "on-failure"` behave differently from `on-request` for websocket clients in this environment?

## Recommended First Code Change

If only one change is made first, it should be:

- change `ThreadStartParams.approvalPolicy` and `ThreadResumeParams.approvalPolicy` from `"never"` to `"on-request"`

That is the smallest change that aligns the app with shell-capable sessions and with the app's existing auto-approval behavior.
