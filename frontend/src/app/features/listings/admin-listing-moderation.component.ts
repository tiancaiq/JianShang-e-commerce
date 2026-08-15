import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { forkJoin } from 'rxjs';
import {
  AdminListingModerationCase,
  ListingModerationCaseFilter,
} from '../../core/models/listing.model';
import { ListingService } from '../../core/services/listing.service';
import { ToastService } from '../../core/services/toast.service';
import { AdminService } from '../../core/services/admin.service';
import { listingModerationCapabilities } from './listing-moderation-capability';
import { ADMIN_PERMISSIONS } from '../../core/security/admin-permissions';

@Component({
  selector: 'app-admin-listing-moderation',
  standalone: true,
  imports: [DatePipe, DecimalPipe, FormsModule],
  template: `
    <section class="moderation-page">
      <header class="page-header">
        <div>
          <h1>Listing Review Cases</h1>
          <p>Claim submitted listing cases before opening the review detail.</p>
        </div>
        <button type="button" class="secondary-btn" (click)="loadQueue()" [disabled]="loading()">Refresh</button>
      </header>

      <div class="filter-bar" aria-label="Listing case filters">
        @for (filter of filters; track filter.value) {
          <button
            type="button"
            class="filter-btn"
            [class.active]="selectedFilter() === filter.value"
            [attr.aria-pressed]="selectedFilter() === filter.value"
            (click)="setFilter(filter.value)"
            [disabled]="loading()"
          >
            {{ filter.label }}
          </button>
        }
      </div>

      <form
        class="search-bar"
        data-testid="listing-case-search-form"
        (ngSubmit)="searchQueue()"
      >
        <label>
          <span>Search</span>
          <input
            name="listing-case-search"
            data-testid="listing-case-search"
            [(ngModel)]="searchQuery"
            (ngModelChange)="onSearchInputChange($event)"
            [disabled]="loading()"
            placeholder="Case, listing, seller, admin, title, SKU"
            maxlength="120"
          >
        </label>
        <button type="submit" class="secondary-btn" [disabled]="loading()">Search</button>
        @if (submittedSearch()) {
          <button
            type="button"
            class="secondary-btn"
            data-testid="listing-case-search-clear"
            [disabled]="loading()"
            (click)="clearSearch()"
          >
            Clear
          </button>
        }
      </form>

      @if (errorMsg()) {
        <div class="error-message" role="alert">{{ errorMsg() }}</div>
      }

      @if (loading()) {
        <div class="empty-state" role="status">Loading listing moderation cases...</div>
      } @else if (cases().length === 0) {
        <div class="empty-state">{{ emptyStateMessage() }}</div>
      } @else {
        <div class="case-stack">
          @for (moderationCase of cases(); track moderationCase.id) {
            <article class="case-panel">
              <div class="case-main">
                <div class="case-title">
                  <div class="eyebrow">{{ moderationCase.sellerType }} / {{ moderationCase.priority }}</div>
                  <h2>{{ moderationCase.title }}</h2>
                  <p>{{ locationFor(moderationCase) }} · {{ moderationCase.quantity }} available</p>
                </div>

                <div class="case-badges">
                  <span class="status-chip">{{ moderationCase.caseStatus }}</span>
                  <span class="status-chip muted">{{ moderationCase.listingStatus }} / {{ moderationCase.listingModerationStatus }}</span>
                </div>
              </div>

              <dl class="meta-grid">
                <div>
                  <dt>Case ID</dt>
                  <dd>{{ moderationCase.id }}</dd>
                </div>
                <div>
                  <dt>Listing ID</dt>
                  <dd>{{ moderationCase.listingId }}</dd>
                </div>
                <div>
                  <dt>Submitted By</dt>
                  <dd class="identity-value">
                    <span>{{ moderationCase.sellerDisplayName || moderationCase.sellerId || moderationCase.submittedByUserId }}</span>
                    @if (moderationCase.sellerDisplayName) {
                      <small>{{ moderationCase.sellerId || moderationCase.submittedByUserId }}</small>
                    }
                  </dd>
                </div>
                <div>
                  <dt>Assigned Admin</dt>
                  <dd class="identity-value">
                    @if (moderationCase.assignedAdminUserId) {
                      <span>{{ moderationCase.assignedAdminDisplayName || moderationCase.assignedAdminUserId }}</span>
                    } @else {
                      <span>Unassigned</span>
                    }
                  </dd>
                </div>
                <div>
                  <dt>Submitted</dt>
                  <dd>{{ moderationCase.createdAt | date: 'medium' }}</dd>
                </div>
                <div>
                  <dt>Price</dt>
                  <dd>{{ moderationCase.priceAmount | number: '1.2-2' }} {{ moderationCase.currency }}</dd>
                </div>
                @if (moderationCase.sku) {
                  <div>
                    <dt>SKU</dt>
                    <dd>{{ moderationCase.sku }}</dd>
                  </div>
                }
                <div>
                  <dt>Version</dt>
                  <dd>{{ moderationCase.version }}</dd>
                </div>
              </dl>

              <div class="action-bar">
                @if (capabilitiesFor(moderationCase).canClaim) {
                  <button
                    type="button"
                    class="primary-btn"
                    (click)="claim(moderationCase)"
                    [disabled]="actionId() === moderationCase.id"
                  >
                    Claim
                  </button>
                }
                @if (capabilitiesFor(moderationCase).canRelease) {
                  <button
                    type="button"
                    class="primary-btn"
                    (click)="openDetail(moderationCase)"
                  >
                    Open review
                  </button>
                  <button
                    type="button"
                    class="secondary-btn"
                    (click)="release(moderationCase)"
                    [disabled]="actionId() === moderationCase.id"
                  >
                    Release
                  </button>
                }
                @if (capabilitiesFor(moderationCase).isReadOnly) {
                  <button
                    type="button"
                    class="secondary-btn"
                    (click)="openDetail(moderationCase)"
                  >
                    View detail
                  </button>
                }
              </div>
            </article>
          }
        </div>
      }
    </section>
  `,
  styles: [`
    .moderation-page {
      display: flex;
      flex-direction: column;
      gap: 1rem;
      max-width: 1120px;
    }

    .page-header,
    .case-main,
    .action-bar,
    .filter-bar,
    .search-bar,
    .case-badges {
      display: flex;
      gap: 0.75rem;
    }

    .page-header,
    .case-main {
      justify-content: space-between;
      align-items: flex-start;
    }

    h1,
    h2 {
      margin: 0;
      color: var(--color-text-primary);
      font-family: var(--font-display);
      letter-spacing: 0;
    }

    h1 {
      font-size: 1.75rem;
    }

    h2 {
      font-size: 1.15rem;
    }

    p {
      margin: 0.35rem 0 0;
      color: var(--color-text-secondary);
      line-height: 1.5;
    }

    .filter-bar {
      flex-wrap: wrap;
    }

    .search-bar {
      align-items: end;
      flex-wrap: wrap;
    }

    .search-bar label {
      display: grid;
      gap: 0.35rem;
      min-width: min(100%, 340px);
      color: var(--color-text-secondary);
      font-weight: 800;
    }

    input {
      min-height: 40px;
      border: 1px solid var(--color-border);
      border-radius: var(--radius-md);
      background: var(--color-bg-primary);
      color: var(--color-text-primary);
      padding: 0.65rem 0.75rem;
      font: inherit;
    }

    .case-stack {
      display: flex;
      flex-direction: column;
      gap: 0.9rem;
    }

    .case-panel,
    .empty-state,
    .error-message {
      border: 1px solid var(--color-border);
      border-radius: var(--radius-lg);
      background: var(--color-bg-secondary);
      padding: 1.15rem;
    }

    .case-panel {
      display: flex;
      flex-direction: column;
      gap: 1rem;
    }

    .eyebrow {
      color: var(--color-info);
      font-size: 0.75rem;
      font-weight: 800;
      letter-spacing: 0.04em;
    }

    .status-chip {
      display: inline-flex;
      align-items: center;
      min-height: 30px;
      border-radius: var(--radius-md);
      background: rgba(34, 197, 94, 0.12);
      color: var(--color-success);
      padding: 0.25rem 0.6rem;
      font-size: 0.78rem;
      font-weight: 800;
      white-space: nowrap;
    }

    .status-chip.muted {
      background: var(--color-bg-primary);
      color: var(--color-text-secondary);
    }

    .meta-grid {
      display: grid;
      grid-template-columns: repeat(4, minmax(0, 1fr));
      gap: 0.75rem;
      margin: 0;
    }

    dt {
      color: var(--color-text-muted);
      font-size: 0.75rem;
      margin-bottom: 0.2rem;
    }

    dd {
      margin: 0;
      color: var(--color-text-primary);
      overflow-wrap: anywhere;
    }

    .identity-value {
      display: flex;
      flex-direction: column;
      gap: 0.15rem;
    }

    .identity-value small {
      color: var(--color-text-muted);
      font-size: 0.75rem;
      line-height: 1.35;
      overflow-wrap: anywhere;
    }

    button {
      min-height: 40px;
      border: 0;
      border-radius: var(--radius-md);
      padding: 0.65rem 0.95rem;
      font: inherit;
      font-weight: 800;
      cursor: pointer;
    }

    button:disabled {
      opacity: 0.6;
      cursor: not-allowed;
    }

    .primary-btn {
      background: var(--color-accent);
      color: #07111f;
    }

    .secondary-btn,
    .filter-btn {
      border: 1px solid var(--color-border);
      background: var(--color-bg-secondary);
      color: var(--color-text-primary);
    }

    .filter-btn.active {
      border-color: var(--color-accent);
      color: var(--color-accent);
      background: rgba(56, 189, 248, 0.1);
    }

    .empty-state {
      color: var(--color-text-secondary);
    }

    .error-message {
      color: var(--color-danger);
      border-color: rgba(244, 63, 94, 0.45);
      background: rgba(244, 63, 94, 0.08);
    }

    @media (max-width: 900px) {
      .page-header,
      .case-main,
      .search-bar,
      .case-badges {
        flex-direction: column;
      }

      .meta-grid {
        grid-template-columns: repeat(2, minmax(0, 1fr));
      }
    }

    @media (max-width: 520px) {
      .moderation-page {
        gap: 0.75rem;
      }

      .case-stack {
        gap: 0.65rem;
      }

      .case-panel {
        gap: 0.7rem;
        padding: 0.8rem;
      }

      .meta-grid {
        gap: 0.55rem;
      }

      .action-bar {
        flex-wrap: wrap;
        gap: 0.5rem;
      }

      .action-bar button {
        flex: 1 1 120px;
      }
    }
  `],
})
export class AdminListingModerationComponent implements OnInit {
  private readonly listingService = inject(ListingService);
  private readonly toastService = inject(ToastService);
  private readonly router = inject(Router);
  private readonly adminService = inject(AdminService);

  readonly filters: { value: ListingModerationCaseFilter; label: string }[] = [
    { value: 'open', label: 'Open' },
    { value: 'unassigned', label: 'Unassigned' },
    { value: 'assigned_to_me', label: 'Assigned to me' },
    { value: 'resolved', label: 'Resolved' },
  ];

  cases = signal<AdminListingModerationCase[]>([]);
  selectedFilter = signal<ListingModerationCaseFilter>('open');
  submittedSearch = signal('');
  loading = signal(false);
  actionId = signal('');
  errorMsg = signal('');
  searchQuery = '';
  currentAdminUserId = signal('');

  ngOnInit(): void {
    this.loadQueue();
  }

  loadQueue(preserveMessage = false): void {
    this.loading.set(true);
    if (!preserveMessage) {
      this.errorMsg.set('');
    }

    forkJoin({
      cases: this.listingService.getListingModerationCases(this.selectedFilter(), this.submittedSearch()),
      admin: this.adminService.getCurrentAdmin(),
    }).subscribe({
      next: response => {
        this.cases.set(response.cases);
        this.currentAdminUserId.set(response.admin.data.userId);
        this.loading.set(false);
      },
      error: error => {
        this.loading.set(false);
        this.handleError(error, 'Listing moderation cases could not be loaded.');
      },
    });
  }

  setFilter(filter: ListingModerationCaseFilter): void {
    if (this.selectedFilter() === filter) {
      return;
    }
    this.selectedFilter.set(filter);
    this.loadQueue();
  }

  searchQueue(): void {
    this.submittedSearch.set(this.searchQuery.trim());
    this.loadQueue();
  }

  onSearchInputChange(query: string): void {
    if (query.trim() || !this.submittedSearch()) {
      return;
    }
    this.submittedSearch.set('');
    this.loadQueue();
  }

  clearSearch(): void {
    this.searchQuery = '';
    this.submittedSearch.set('');
    this.loadQueue();
  }

  claim(moderationCase: AdminListingModerationCase): void {
    this.actionId.set(moderationCase.id);
    this.errorMsg.set('');

    this.listingService.claimListingModerationCase(moderationCase.id, moderationCase.version).subscribe({
      next: updatedCase => {
        this.replaceCase(updatedCase);
        this.actionId.set('');
        this.toastService.success('Listing moderation case claimed.');
      },
      error: error => {
        this.actionId.set('');
        this.handleError(error, 'Listing moderation case could not be claimed.');
      },
    });
  }

  release(moderationCase: AdminListingModerationCase): void {
    this.actionId.set(moderationCase.id);
    this.errorMsg.set('');

    this.listingService.releaseListingModerationCase(moderationCase.id, moderationCase.version).subscribe({
      next: updatedCase => {
        this.replaceCase(updatedCase);
        this.actionId.set('');
        this.toastService.success('Listing moderation case released.');
      },
      error: error => {
        this.actionId.set('');
        this.handleError(error, 'Listing moderation case could not be released.');
      },
    });
  }

  openDetail(moderationCase: AdminListingModerationCase): void {
    this.router.navigate(['/admin/listings/moderation', moderationCase.id]);
  }

  capabilitiesFor(moderationCase: AdminListingModerationCase) {
    return listingModerationCapabilities(moderationCase, this.currentAdminUserId(), {
      canClaim: this.adminService.hasPermission(ADMIN_PERMISSIONS.LISTING_MODERATION_CLAIM),
      canResolve: this.adminService.hasPermission(ADMIN_PERMISSIONS.LISTING_MODERATION_RESOLVE),
    });
  }

  locationFor(moderationCase: AdminListingModerationCase): string {
    if (!moderationCase.publicCity && !moderationCase.publicRegion) {
      return 'Location not set';
    }
    return [moderationCase.publicCity, moderationCase.publicRegion].filter(Boolean).join(', ');
  }

  emptyStateMessage(): string {
    if (this.submittedSearch()) {
      return 'No listing moderation cases match this search.';
    }
    switch (this.selectedFilter()) {
      case 'assigned_to_me':
        return 'No listing review cases are assigned to you.';
      case 'unassigned':
        return 'No unassigned listing review cases.';
      case 'resolved':
        return 'No resolved listing review cases.';
      default:
        return 'No open listing review cases.';
    }
  }

  private replaceCase(updatedCase: AdminListingModerationCase): void {
    this.cases.update(items => items.map(item => item.id === updatedCase.id ? updatedCase : item));
  }

  private handleError(error: { status?: number }, fallback: string): void {
    if (error.status === 401) {
      this.router.navigate(['/login']);
      return;
    }
    if (error.status === 403) {
      this.errorMsg.set('You need platform admin access for this action.');
      return;
    }
    if (error.status === 409) {
      this.errorMsg.set('Case changed while you were working. Refresh the queue and try again.');
      this.loadQueue(true);
      return;
    }
    this.errorMsg.set(fallback);
  }
}
