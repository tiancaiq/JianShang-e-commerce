import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-seller-dashboard',
  standalone: true,
  imports: [RouterLink],
  template: `
    <section class="seller-dashboard">
      <header>
        <h1>Business Seller Dashboard</h1>
      </header>

      <div class="action-list">
        <a routerLink="/seller/business/apply" class="action-row">
          <strong>Business Apply</strong>
          <span>Application</span>
        </a>
        <a routerLink="/account/profile" class="action-row">
          <strong>Account</strong>
          <span>Account</span>
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
      letter-spacing: 0;
    }

    .action-list {
      display: flex;
      flex-direction: column;
      max-width: 760px;
      overflow: hidden;
      background: var(--color-bg-secondary);
      border: 1px solid var(--color-border);
      border-radius: var(--radius-lg);
    }

    .action-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 1rem;
      min-height: 56px;
      padding: 0.875rem 1rem;
      border-bottom: 1px solid var(--color-border);
      color: var(--color-text-primary);
      text-decoration: none;
    }

    .action-row:last-child {
      border-bottom: 0;
    }

    .action-row:hover {
      background: var(--color-bg-tertiary);
    }

    .action-row strong {
      font-size: 1rem;
    }

    .action-row span {
      color: var(--color-text-muted);
      font-size: 0.875rem;
      font-weight: 700;
    }
  `],
})
export class SellerDashboardComponent {}
