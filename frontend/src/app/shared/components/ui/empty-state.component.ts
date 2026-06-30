import { Component } from '@angular/core';

@Component({
  selector: 'app-ui-empty-state',
  standalone: true,
  template: `
    <section class="ui-empty-state">
      <ng-content />
    </section>
  `,
  styles: [`
    .ui-empty-state {
      border-radius: var(--radius-lg);
      padding: 1rem;
      background: var(--ui-empty-bg, var(--listing-surface, var(--color-bg-secondary)));
      border: 1px solid var(--ui-empty-border, var(--listing-border, var(--color-border)));
      color: var(--ui-empty-text, var(--listing-muted, var(--color-text-secondary)));
      box-shadow: var(--ui-empty-shadow, var(--listing-shadow, none));
    }

    .ui-empty-state ::ng-deep h2,
    .ui-empty-state ::ng-deep h3 {
      margin: 0 0 0.75rem;
      color: var(--ui-empty-heading, var(--listing-text, var(--color-text-primary)));
      font-size: 1rem;
    }

    .ui-empty-state ::ng-deep p {
      margin: 0 0 0.85rem;
      color: var(--ui-empty-muted, var(--listing-subtle, var(--color-text-muted)));
      font-size: 0.875rem;
    }

    .ui-empty-state ::ng-deep p:last-child {
      margin-bottom: 0;
    }
  `],
})
export class EmptyStateComponent {}
