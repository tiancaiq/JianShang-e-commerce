import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { BusinessStoreContext } from '../../core/models/business-store.model';
import { AuthService } from '../../core/services/auth.service';
import { BusinessStoreService } from '../../core/services/business-store.service';

@Component({
  selector: 'app-business-account',
  standalone: true,
  imports: [RouterLink],
  template: `
    <section class="business-account-page">
      <header class="business-account-header">
        <div>
          <p class="eyebrow">Business account</p>
          <h1>{{ context()?.businessLegalName || 'Merchant workspace' }}</h1>
          <p>{{ authService.user()?.displayName || authService.user()?.email }}</p>
        </div>
      </header>

      @if (loading()) {
        <p class="status-note">Loading business store context.</p>
      } @else if (errorMsg()) {
        <p class="status-note error">{{ errorMsg() }}</p>
      } @else if (context()) {
        <p class="status-note">
          {{ context()?.membershipRole }} access for an active business store.
        </p>
      } @else {
        <p class="status-note">
          No approved business store is connected to this account yet.
        </p>
      }

      <div class="account-grid">
        <a routerLink="/seller/business/apply" class="account-action">
          <span>Business onboarding</span>
          <strong>{{ context() ? 'Application approved' : 'Application' }}</strong>
        </a>

        <a [routerLink]="storeProfileLink()" class="account-action">
          <span>Store setup</span>
          <strong>Store profile</strong>
        </a>

        <a [routerLink]="storeItemsLink()" class="account-action">
          <span>Store catalog</span>
          <strong>Store items</strong>
        </a>

        <a routerLink="/account/appeals" class="account-action">
          <span>Decision review</span>
          <strong>Enforcement appeals</strong>
        </a>
      </div>
    </section>
  `,
  styles: [`
    .business-account-page {
      display: flex;
      flex-direction: column;
      gap: 1.25rem;
      max-width: 900px;
    }

    .business-account-header,
    .account-action {
      background: var(--color-bg-secondary);
      border: 1px solid var(--color-border);
      border-radius: var(--radius-lg);
    }

    .business-account-header {
      padding: 1.25rem;
    }

    .eyebrow {
      margin: 0 0 0.35rem;
      color: var(--color-accent);
      font-size: 0.75rem;
      font-weight: 800;
      letter-spacing: 0.08em;
      text-transform: uppercase;
    }

    h1,
    p {
      margin: 0;
    }

    h1 {
      color: var(--color-text-primary);
      font-size: 1.75rem;
      letter-spacing: 0;
    }

    .business-account-header p:not(.eyebrow) {
      margin-top: 0.35rem;
      color: var(--color-text-muted);
      font-size: 0.875rem;
      font-weight: 700;
    }

    .status-note {
      margin: 0;
      padding: 0.75rem 1rem;
      background: var(--color-bg-secondary);
      border: 1px solid var(--color-border);
      border-radius: var(--radius-md);
      color: var(--color-text-muted);
      font-size: 0.875rem;
      font-weight: 750;
    }

    .status-note.error {
      border-color: var(--color-danger);
      color: var(--color-danger);
    }

    .account-grid {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 1rem;
    }

    .account-action {
      min-height: 128px;
      display: flex;
      flex-direction: column;
      justify-content: space-between;
      padding: 1rem;
      color: var(--color-text-primary);
      text-decoration: none;
    }

    .account-action:hover {
      background: var(--color-bg-tertiary);
      border-color: var(--color-accent);
    }

    .account-action-disabled,
    .account-action-disabled:hover {
      cursor: default;
      opacity: 0.72;
      border-color: var(--color-border);
      background: var(--color-bg-secondary);
    }

    .account-action span {
      color: var(--color-text-muted);
      font-size: 0.8125rem;
      font-weight: 750;
    }

    .account-action strong {
      font-size: 1.1rem;
    }

    @media (max-width: 760px) {
      .account-grid {
        grid-template-columns: 1fr;
      }
    }
  `],
})
export class BusinessAccountComponent implements OnInit {
  readonly authService = inject(AuthService);
  private readonly businessStoreService = inject(BusinessStoreService);

  readonly loading = signal(true);
  readonly errorMsg = signal('');
  readonly context = signal<BusinessStoreContext | null>(null);

  ngOnInit(): void {
    this.businessStoreService.getCurrentStoreContext().subscribe({
      next: context => {
        this.context.set(context);
        this.loading.set(false);
      },
      error: () => {
        this.errorMsg.set('Business store context could not be loaded.');
        this.loading.set(false);
      },
    });
  }

  storeProfileLink(): string[] {
    const context = this.context();
    return context ? ['/seller/businesses', context.businessId, 'store'] : ['/seller/business/apply'];
  }

  storeItemsLink(): string[] {
    return this.context() ? ['/seller/store/items'] : ['/seller/business/apply'];
  }
}
