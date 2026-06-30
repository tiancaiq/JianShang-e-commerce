import { Component } from '@angular/core';

@Component({
  selector: 'app-ui-status-pill',
  standalone: true,
  template: `
    <span class="ui-status-pill">
      <ng-content />
    </span>
  `,
  styles: [`
    .ui-status-pill {
      display: inline-flex;
      align-items: center;
      min-height: 28px;
      padding: 0 0.65rem;
      border: 1px solid var(--ui-pill-border, var(--listing-border, var(--profile-border, var(--color-border))));
      border-radius: 999px;
      background: var(--ui-pill-bg, var(--listing-surface, var(--profile-accent-muted, var(--color-accent-muted))));
      color: var(--ui-pill-text, var(--listing-accent, var(--profile-accent, var(--color-accent))));
      font-size: 0.75rem;
      font-weight: 850;
      line-height: 1;
      white-space: nowrap;
    }
  `],
})
export class StatusPillComponent {}
