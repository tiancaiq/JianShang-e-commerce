import { Component, Input } from '@angular/core';

type EmptyStateIllustration = 'cart' | 'orders' | 'seller' | 'assistant';

@Component({
  selector: 'app-ui-empty-state',
  standalone: true,
  template: `
    <section class="ui-empty-state" [class.illustrated]="illustration">
      @if (illustration) {
        <img [src]="illustrationUrl" alt="" aria-hidden="true" />
      }
      <ng-content />
    </section>
  `,
  styles: [`
    .ui-empty-state {
      border-radius: var(--radius-lg);
      padding: 1.25rem;
      background: var(--ui-empty-bg, var(--listing-surface, var(--color-bg-secondary)));
      border: 1px solid var(--ui-empty-border, var(--listing-border, var(--color-border)));
      color: var(--ui-empty-text, var(--listing-muted, var(--color-text-secondary)));
      box-shadow: var(--ui-empty-shadow, var(--listing-shadow, none));
    }

    .ui-empty-state.illustrated {
      display: grid;
      justify-items: center;
      padding: clamp(1.4rem, 4vw, 2.25rem);
      text-align: center;
    }

    img {
      width: min(300px, 82%);
      max-height: 220px;
      margin: -0.5rem auto 0.5rem;
      object-fit: contain;
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
export class EmptyStateComponent {
  @Input() illustration: EmptyStateIllustration | null = null;

  get illustrationUrl(): string {
    return this.illustration === 'cart'
      ? '/assets/brand/anime/empty-cart-mascot.webp'
      : this.illustration === 'orders'
        ? '/assets/brand/anime/parcel-mascot.svg'
        : this.illustration === 'assistant'
          ? '/assets/brand/anime/assistant-mascot.webp'
          : '/assets/brand/anime/seller-studio-anime.webp';
  }
}
