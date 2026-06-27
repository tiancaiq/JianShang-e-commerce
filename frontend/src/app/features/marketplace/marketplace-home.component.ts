import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-marketplace-home',
  standalone: true,
  imports: [RouterLink],
  template: `
    <section class="marketplace-home">
      <div class="intro">
        <p class="eyebrow">Marketplace</p>
        <h1>Find local listings and trusted stores.</h1>
        <p class="summary">
          Browse is public. Seller tools live in a separate portal.
        </p>
        <div class="actions">
          <a routerLink="/seller" class="primary-link">Seller Portal</a>
          <a routerLink="/login" class="secondary-link">Sign in</a>
        </div>
      </div>

      <div class="browse-panel">
        <div class="panel-header">
          <h2>Public Browse</h2>
          <span>Coming next</span>
        </div>
        <div class="empty-list">
          Approved listing browse will appear here after the listing publish and search slices.
        </div>
      </div>
    </section>
  `,
  styles: [`
    .marketplace-home {
      display: grid;
      grid-template-columns: minmax(0, 0.85fr) minmax(320px, 1.15fr);
      gap: 2rem;
      align-items: start;
    }

    .intro {
      padding: 3rem 0;
    }

    .eyebrow {
      color: var(--color-accent);
      font-size: 0.8125rem;
      font-weight: 700;
      text-transform: uppercase;
      letter-spacing: 0.08em;
      margin-bottom: 0.75rem;
    }

    h1 {
      max-width: 640px;
      font-size: 3rem;
      line-height: 1.05;
      margin-bottom: 1rem;
      letter-spacing: 0;
    }

    .summary {
      max-width: 560px;
      color: var(--color-text-secondary);
      font-size: 1rem;
      margin-bottom: 1.5rem;
    }

    .actions {
      display: flex;
      gap: 0.75rem;
      flex-wrap: wrap;
    }

    .primary-link,
    .secondary-link {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      min-height: 42px;
      padding: 0 1rem;
      border-radius: var(--radius-md);
      font-weight: 700;
      text-decoration: none;
    }

    .primary-link {
      background: var(--color-accent);
      color: #0c0c0e;
    }

    .secondary-link {
      border: 1px solid var(--color-border);
      color: var(--color-text-secondary);
    }

    .browse-panel {
      background: var(--color-bg-secondary);
      border: 1px solid var(--color-border);
      border-radius: var(--radius-lg);
      overflow: hidden;
    }

    .panel-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 1rem;
      padding: 1rem 1.25rem;
      border-bottom: 1px solid var(--color-border);
    }

    .panel-header h2 {
      font-size: 1.125rem;
      letter-spacing: 0;
    }

    .panel-header span {
      color: var(--color-text-muted);
      font-size: 0.8125rem;
      font-weight: 700;
    }

    .empty-list {
      min-height: 260px;
      display: grid;
      place-items: center;
      padding: 1.5rem;
      color: var(--color-text-muted);
      text-align: center;
    }

    @media (max-width: 900px) {
      .marketplace-home {
        grid-template-columns: 1fr;
      }

      h1 {
        font-size: 2.25rem;
      }
    }
  `],
})
export class MarketplaceHomeComponent {}
