import { Component, OnInit, inject, signal } from '@angular/core';
import { CurrencyPipe, DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { BusinessStoreContext } from '../../core/models/business-store.model';
import { ListingDraft } from '../../core/models/listing.model';
import { BusinessStoreService } from '../../core/services/business-store.service';
import { ListingService } from '../../core/services/listing.service';

@Component({
  selector: 'app-business-store-items',
  standalone: true,
  imports: [CurrencyPipe, DatePipe, RouterLink],
  template: `
    <section class="store-items-page">
      <header class="page-header">
        <div>
          <p class="eyebrow">Store catalog</p>
          <h1>{{ context()?.store?.name || 'Store items' }}</h1>
          <p>Draft and edit the items your approved business will later publish to the store surface.</p>
        </div>

        @if (context()) {
          <a routerLink="/seller/store/items/new" class="primary-action">New item</a>
        }
      </header>

      @if (loadingContext() || loadingItems()) {
        <p class="status-note">Loading store items.</p>
      } @else if (errorMsg()) {
        <p class="status-note error">{{ errorMsg() }}</p>
      } @else if (!context()) {
        <div class="empty-state">
          <h2>No approved store yet</h2>
          <p>Finish business approval before adding store item drafts.</p>
          <a routerLink="/seller/business/apply" class="secondary-action">Business application</a>
        </div>
      } @else if (items().length === 0) {
        <div class="empty-state">
          <h2>No item drafts</h2>
          <p>Create the first store item draft for {{ context()?.store?.name }}.</p>
          <a routerLink="/seller/store/items/new" class="primary-action">New item</a>
        </div>
      } @else {
        <div class="table-shell">
          <table>
            <thead>
              <tr>
                <th>Item</th>
                <th>SKU</th>
                <th>Qty</th>
                <th>Price</th>
                <th>Status</th>
                <th>Updated</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              @for (item of items(); track item.id) {
                <tr>
                  <td>
                    <strong>{{ item.title }}</strong>
                    <span>{{ conditionLabel(item.condition) }}</span>
                  </td>
                  <td>{{ item.sku || 'Not set' }}</td>
                  <td>{{ item.quantity }}</td>
                  <td>{{ item.priceAmount | currency:item.currency:'symbol':'1.2-2' }}</td>
                  <td>
                    <span class="status-pill">{{ item.status }}</span>
                  </td>
                  <td>{{ item.updatedAt | date:'mediumDate' }}</td>
                  <td>
                    <a [routerLink]="['/seller/store/items', item.id, 'edit']" class="table-action">Edit</a>
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      }
    </section>
  `,
  styles: [`
    .store-items-page {
      display: flex;
      flex-direction: column;
      gap: 1.25rem;
      max-width: 1080px;
    }

    .page-header {
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      gap: 1rem;
      padding: 1.25rem;
      background: var(--color-bg-secondary);
      border: 1px solid var(--color-border);
      border-radius: var(--radius-lg);
    }

    .eyebrow,
    h1,
    h2,
    p {
      margin: 0;
    }

    .eyebrow {
      margin-bottom: 0.35rem;
      color: var(--color-accent);
      font-size: 0.75rem;
      font-weight: 800;
      letter-spacing: 0.08em;
      text-transform: uppercase;
    }

    h1 {
      color: var(--color-text-primary);
      font-size: 1.75rem;
      letter-spacing: 0;
    }

    h2 {
      color: var(--color-text-primary);
      font-size: 1.25rem;
      letter-spacing: 0;
    }

    .page-header p,
    .empty-state p {
      margin-top: 0.4rem;
      color: var(--color-text-muted);
      font-size: 0.875rem;
      font-weight: 700;
    }

    .primary-action,
    .secondary-action,
    .table-action {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      min-height: 40px;
      padding: 0 0.875rem;
      border-radius: var(--radius-md);
      font-weight: 800;
      text-decoration: none;
      white-space: nowrap;
    }

    .primary-action {
      background: var(--color-accent);
      color: var(--color-accent-contrast);
      border: 1px solid transparent;
    }

    .secondary-action,
    .table-action {
      background: var(--color-bg-tertiary);
      color: var(--color-text-primary);
      border: 1px solid var(--color-border);
    }

    .status-note,
    .empty-state,
    .table-shell {
      background: var(--color-bg-secondary);
      border: 1px solid var(--color-border);
      border-radius: var(--radius-lg);
    }

    .status-note {
      margin: 0;
      padding: 0.75rem 1rem;
      color: var(--color-text-muted);
      font-size: 0.875rem;
      font-weight: 750;
    }

    .status-note.error {
      border-color: var(--color-danger);
      color: var(--color-danger);
    }

    .empty-state {
      display: flex;
      flex-direction: column;
      align-items: flex-start;
      gap: 0.85rem;
      padding: 1.25rem;
    }

    .table-shell {
      overflow-x: auto;
    }

    table {
      width: 100%;
      min-width: 760px;
      border-collapse: collapse;
    }

    th,
    td {
      padding: 0.875rem 1rem;
      border-bottom: 1px solid var(--color-border);
      text-align: left;
      vertical-align: middle;
      color: var(--color-text-muted);
      font-size: 0.875rem;
    }

    th {
      color: var(--color-text-primary);
      font-size: 0.75rem;
      font-weight: 800;
      text-transform: uppercase;
      letter-spacing: 0.06em;
    }

    td strong,
    td span {
      display: block;
    }

    td strong {
      color: var(--color-text-primary);
      font-size: 0.95rem;
    }

    .status-pill {
      display: inline-flex;
      min-height: 28px;
      align-items: center;
      padding: 0 0.6rem;
      border-radius: 999px;
      background: var(--color-bg-tertiary);
      border: 1px solid var(--color-border);
      color: var(--color-text-primary);
      font-size: 0.75rem;
      font-weight: 800;
    }

    tr:last-child td {
      border-bottom: 0;
    }

    @media (max-width: 760px) {
      .page-header {
        flex-direction: column;
      }

      .primary-action,
      .secondary-action {
        width: 100%;
      }
    }
  `],
})
export class BusinessStoreItemsComponent implements OnInit {
  private readonly businessStoreService = inject(BusinessStoreService);
  private readonly listingService = inject(ListingService);

  readonly loadingContext = signal(true);
  readonly loadingItems = signal(false);
  readonly errorMsg = signal('');
  readonly context = signal<BusinessStoreContext | null>(null);
  readonly items = signal<ListingDraft[]>([]);

  ngOnInit(): void {
    this.businessStoreService.getCurrentStoreContext().subscribe({
      next: context => {
        this.context.set(context);
        this.loadingContext.set(false);
        if (context) {
          this.loadItems(context.businessId);
        }
      },
      error: () => {
        this.loadingContext.set(false);
        this.errorMsg.set('Business store context could not be loaded.');
      },
    });
  }

  conditionLabel(condition: string): string {
    return condition.replace(/_/g, ' ').toLowerCase();
  }

  private loadItems(businessId: string): void {
    this.loadingItems.set(true);
    this.listingService.getBusinessStoreItems(businessId).subscribe({
      next: items => {
        this.items.set(items);
        this.loadingItems.set(false);
      },
      error: () => {
        this.errorMsg.set('Store item drafts could not be loaded.');
        this.loadingItems.set(false);
      },
    });
  }
}
