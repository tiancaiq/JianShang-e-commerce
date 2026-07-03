# PRESEARCH-STAB-P2-01 Marketplace CSS Budget Cleanup

Status: complete.

## Goal

Reduce marketplace CSS budget noise before search UI adds more styles.

This cleanup should not redesign the UI.

## Scope

Review CSS for:

- marketplace home
- marketplace layout
- repeated listing card styles
- repeated token values that already have a shared home

## Tasks

- Remove duplicated CSS rules where safe.
- Move repeated style tokens only when an existing shared place is appropriate.
- Keep layout, colors, spacing, and responsive behavior unchanged.
- Record whether the Angular build warning is reduced or still accepted.

## Non-Goals

- No marketplace redesign.
- No new components unless they replace obvious duplication.
- No behavior changes.
- No search UI work.

## Verification

```powershell
cd frontend
npm.cmd run build
```

## Acceptance Criteria

- CSS budget warnings are reduced or explicitly documented.
- Visible UI behavior is unchanged.

## Completion Notes

- Removed dead marketplace layout CSS from the older popup-auth path:
  `.auth-actions`, `.auth-secondary`, and `.auth-divider`.
- The marketplace layout component budget warning was reduced from roughly
  `5.31 kB` to `4.69 kB`.
- The marketplace home component still warns at `4.81 kB`; this is documented
  instead of forcing a risky visual compression during stabilization.
- No UI behavior, route, API, or schema change was made.

## Verification Results

```powershell
cd frontend
npm.cmd run build
```

Result: build succeeded. Remaining warnings:

- `marketplace-layout.component.ts`: budget `4.00 kB`, actual `4.69 kB`.
- `marketplace-home.component.ts`: budget `4.00 kB`, actual `4.81 kB`.
