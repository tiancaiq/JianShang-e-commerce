import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-seller-dashboard',
  standalone: true,
  imports: [RouterLink],
  template: `
    <section class="seller-dashboard">
      <header>
        <h1>Seller Dashboard</h1>
        <p>Manage seller setup and draft listings.</p>
      </header>

      <div class="action-grid">
        <a routerLink="/seller/listings/new" class="action-card">
          <strong>New Listing</strong>
          <span>Create a draft listing with images.</span>
        </a>
        <a routerLink="/seller/activate" class="action-card">
          <strong>Individual Seller</strong>
          <span>Activate or review your individual seller profile.</span>
        </a>
        <a routerLink="/seller/business/apply" class="action-card">
          <strong>Business Apply</strong>
          <span>Submit a business seller application.</span>
        </a>
      </div>
    </section>
  `,
  styles: [`
    .seller-dashboard {
      display: flex;
      flex-direction: column;
      gap: 1.5rem;
    }

    header h1 {
      font-size: 1.75rem;
      margin-bottom: 0.25rem;
      letter-spacing: 0;
    }

    header p {
      color: var(--color-text-muted);
      font-size: 0.9375rem;
    }

    .action-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
      gap: 1rem;
      max-width: 860px;
    }

    .action-card {
      display: flex;
      flex-direction: column;
      gap: 0.375rem;
      min-height: 120px;
      padding: 1rem;
      background: var(--color-bg-secondary);
      border: 1px solid var(--color-border);
      border-radius: var(--radius-lg);
      color: var(--color-text-primary);
      text-decoration: none;
    }

    .action-card:hover {
      border-color: var(--color-accent);
    }

    .action-card strong {
      font-size: 1rem;
    }

    .action-card span {
      color: var(--color-text-muted);
      font-size: 0.875rem;
    }
  `],
})
export class SellerDashboardComponent {}
