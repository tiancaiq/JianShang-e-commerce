# AI-SKILL-CLI-00 — Skills and CLI foundation

## Status and boundary

This slice adds a first-class, inert Skill library and a developer/operator CLI to Marketplace Agent V2. It does not replace the controlled ReAct loop, runtime tool registry, capability boundary, proposal policy, durable confirmation path, SSE contract, RAG, or Java service adapters. It adds no public API, database migration, or frontend behavior.

## What a Skill is

A Skill is versioned procedural guidance stored as a `SKILL.md` file. Its strict front matter declares a stable name, compact description, version, customer surface, and the existing registered tools the procedure may use. Its Markdown body describes preconditions, workflow guidance, and stop conditions.

A Skill is data, not executable code. The runtime reads UTF-8 text and never passes it to `exec`, `eval`, a subprocess, SQL, HTTP, or a service adapter.

### Skill versus Tool

- A Skill tells the model how to approach a reusable workflow.
- A Tool is an executable, schema-validated capability in the existing runtime registry.
- Selecting or loading a Skill performs no marketplace read or write.
- Every tool proposal still passes the existing capability, surface, schema, actor, duplicate-call, grounding, and confirmation policy before an adapter can run.

### Skill versus RAG

Skills are maintained application instructions about procedure. RAG retrieves approved, versioned marketplace reference facts and citations. Skill text is not customer evidence, does not prove inventory or private account state, and cannot override authoritative Product, Order, policy, or confirmation results.

## Architecture

```text
Customer message
      |
      v
+-----------------------+
| Marketplace Agent V2  |
| five-decision budget  |
+----------+------------+
           | compact Skill names/descriptions only
           v
+-----------------------+
| model selects Skill   |---- natural answer (no Skill needed)
+----------+------------+
           | internal load decision; no marketplace I/O
           v
+-----------------------+
| deterministic Skill   |
| registry loads body   |
+----------+------------+
           | existing enabled allowed-tool subset
           v
+-----------------------+       +----------------------+
| controlled ReAct loop |------>| tool policy/capability|
+-----------------------+       +----------+-----------+
                                         |
                              registered adapter only
                                         |
                                         v
                                authoritative Java services
```

## Registry and validation

The deterministic registry lives in `marketplace_agent_v2/skill_registry.py`. It discovers sorted immediate child directories, requires one `SKILL.md` per canonical directory, parses only a deliberately small YAML-front-matter subset, and rejects:

- missing or malformed metadata;
- missing or empty instructions;
- invalid directory or Skill names;
- directory/name mismatches;
- duplicate Skill names;
- invalid or duplicate allowed-tool entries;
- tools absent from the authoritative customer capability catalog;
- unsupported surfaces or metadata fields.

At runtime, a Skill is offered only when every declared tool is currently enabled for the customer surface. A Skill can reduce the visible tool set for the next decision, but it cannot add a disabled or unknown tool.

## Progressive disclosure and lifecycle

Before selection, the model receives only compact Skill metadata and a bounded `load_skill` control schema. `load_skill` is parsed into a Skill-selection decision rather than a marketplace `ToolProposal`; it performs no I/O and consumes one of the existing five model decisions.

After a valid selection, the next decision receives the full instructions and only the intersection of the Skill's allowlist with the already-enabled runtime registry. A model-proposed action still enters the normal tool policy. Unknown, unavailable, or repeated Skill selection fails closed.

Safe structured log events include `skill_selected`, `skill_loaded`, `skill_completed`, `skill_abandoned`, and `skill_validation_failed`, with bounded Skill name, correlation ID, outcome, and error category. Skill text, private model reasoning, raw tool payloads, and private customer data are not logged.

## Initial Skills

- `marketplace-discovery`: current listing search, category availability, and listing revalidation.
- `manage-cart`: authenticated cart reads and explicit reversible cart changes.
- `purchase-item`: whole-cart checkout preparation and the existing durable confirmation boundary.
- `order-help`: owned order reads plus supported cancellation and return preparation boundaries.

The mutation Skills explicitly instruct the model never to propose confirmation-gated execution tools directly. The backend policy independently rejects such proposals with `CONFIRMATION_REQUIRED`; Skill wording is never trusted as authorization.

## CLI

The Python package exposes the `agent` console command:

```bash
agent skills list
agent skills show purchase-item
agent skills show purchase-item --body
agent skills validate
agent skills validate purchase-item
agent skills tools purchase-item
```

`validate` returns `0` for a valid library and non-zero for malformed metadata, directory errors, duplicate names, unknown tools, or an unknown requested Skill. The hidden `--skills-dir` option exists for isolated tests and developer validation of a candidate library.

## Adding and testing a Skill

1. Create `marketplace_agent_v2/skills/<canonical-name>/SKILL.md`.
2. Add strict front matter with `name`, `description`, positive integer `version`, `surface: customer`, and a non-empty `allowed_tools` list.
3. Reference only names already present in the customer capability catalog; do not add Skill-specific duplicate tools.
4. Write concise workflow, precondition, and stop-condition instructions. Never place secrets, executable content, direct database access, or authority claims in a Skill.
5. Run `agent skills validate <canonical-name>` and the focused Skill tests.
6. Add an Agent test proving selection, expected existing tool guidance, policy enforcement, confirmation preservation, disabled-capability behavior, and an unrelated no-Skill conversation.

## Deferred follow-ups

This foundation intentionally does not add persistent cross-turn Skill state, admin-authored Skills, remote Skill downloads, hot reload, arbitrary scripting, new tools, new commerce workflows, or a customer-visible Skill UI. Future Skills should be added in small approved slices with evaluation fixtures and rollout evidence.
