import { CurrencyPipe, DatePipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { BusinessStoreContext } from '../../core/models/business-store.model';
import {
  BusinessStoreItemManagementStatus,
  BusinessStoreItemSearchParams,
  BusinessStoreItemStatusSummary,
  ListingDraft,
  ListingImage,
} from '../../core/models/listing.model';
import { BusinessStoreService } from '../../core/services/business-store.service';
import { ListingService } from '../../core/services/listing.service';
import { EmptyStateComponent } from '../../shared/components/ui/empty-state.component';

type CatalogStatusFilter = BusinessStoreItemManagementStatus | 'ALL';

@Component({
  selector: 'app-business-store-items',
  standalone: true,
  imports: [CurrencyPipe, DatePipe, EmptyStateComponent, FormsModule, RouterLink],
  template: `
    <section class="catalog-page">
      <header class="page-header">
        <div class="header-copy">
          <p class="eyebrow">Store catalog</p>
          <h1>{{ context()?.store?.name || 'Business items' }}</h1>
          <p>Manage the products listed by your approved business.</p>
        </div>

        @if (context()) {
          <a
            routerLink="/seller/store/items/new"
            [queryParams]="listQueryParams()"
            class="primary-action"
          >
            New item
          </a>
        }
      </header>

      @if (loadingContext()) {
        <app-ui-empty-state>Loading your business catalog.</app-ui-empty-state>
      } @else if (!context() && errorMsg()) {
        <div class="message error" role="alert">{{ errorMsg() }}</div>
      } @else if (!context()) {
        <app-ui-empty-state>
          <h2>No approved store yet</h2>
          <p>Finish business approval before adding business items.</p>
          <a routerLink="/seller/business/apply" class="secondary-action">Business application</a>
        </app-ui-empty-state>
      } @else {
        <nav class="status-rail" aria-label="Catalog status">
          @for (option of statusOptions; track option.value) {
            <button
              type="button"
              class="status-option"
              [class.selected]="selectedStatus === option.value"
              [attr.aria-pressed]="selectedStatus === option.value"
              (click)="selectStatus(option.value)"
            >
              <span>{{ option.label }}</span>
              <strong>{{ statusCount(option.value) }}</strong>
            </button>
          }
        </nav>

        <section class="catalog-tools" aria-label="Catalog filters">
          <form class="search-form" (ngSubmit)="applySearch()">
            <label for="business-item-search">Search items</label>
            <div class="search-controls">
              <input
                id="business-item-search"
                name="businessItemSearch"
                [(ngModel)]="searchTerm"
                maxlength="120"
                placeholder="Title or SKU"
                autocomplete="off"
              />
              @if (searchTerm.trim()) {
                <button type="button" class="clear-action" (click)="clearSearch()">Clear</button>
              }
              <button type="submit" class="search-action">Search</button>
            </div>
          </form>

          <p class="result-count" aria-live="polite">
            {{ items().length }} {{ items().length === 1 ? 'item' : 'items' }}
            @if (hasMore()) {
              <span>shown so far</span>
            }
          </p>
        </section>

        @if (errorMsg()) {
          <div class="message error" role="alert">{{ errorMsg() }}</div>
        }
        @if (successMsg()) {
          <div class="message success" role="status">{{ successMsg() }}</div>
        }

        @if (loadingItems() && items().length === 0) {
          <div class="catalog-loading" aria-live="polite">
            <span></span>
            <span></span>
            <span></span>
            <p>Loading catalog items.</p>
          </div>
        } @else if (items().length === 0) {
          <app-ui-empty-state>
            @if (summary().total === 0) {
              <h2>No business items yet</h2>
              <p>Create your first item, add images, then publish it to the business catalog.</p>
              <a
                routerLink="/seller/store/items/new"
                [queryParams]="listQueryParams()"
                class="primary-action"
              >
                New item
              </a>
            } @else {
              <h2>No matching items</h2>
              <p>Change the search or status filter to see more of your catalog.</p>
              <button type="button" class="secondary-action" (click)="clearFilters()">Clear filters</button>
            }
          </app-ui-empty-state>
        } @else {
          <section class="catalog-list" aria-label="Business catalog items">
            <div class="catalog-columns" aria-hidden="true">
              <span>Item</span>
              <span>Price</span>
              <span>Quantity</span>
              <span>Status</span>
              <span>Updated</span>
              <span>Actions</span>
            </div>

            @for (item of items(); track item.id) {
              <article class="catalog-row">
                <div class="item-cell">
                  <div class="item-image">
                    @if (imageUrl(item)) {
                      <img [src]="imageUrl(item)" [alt]="imageAlt(item)" />
                    } @else {
                      <span aria-hidden="true">No image</span>
                    }
                  </div>
                  <div class="item-copy">
                    @if (item.status === 'ACTIVE') {
                      <a [routerLink]="['/listings', item.id]" class="item-title">{{ item.title }}</a>
                    } @else {
                      <strong class="item-title">{{ item.title }}</strong>
                    }
                    <span class="sku">{{ item.sku || 'SKU not set' }}</span>
                    <span class="condition">{{ conditionLabel(item.condition) }}</span>
                    @if (item.status === 'REMOVED_BY_ADMIN' && item.moderationReason) {
                      <span class="moderation-note">
                        Removed by admin: {{ item.moderationReason }}
                      </span>
                    }
                  </div>
                </div>

                <div class="data-cell" data-label="Price">
                  <strong>{{ item.priceAmount | currency:item.currency:'symbol':'1.2-2' }}</strong>
                </div>

                <div class="data-cell" data-label="Quantity">
                  <strong>Quantity listed: {{ item.quantity }}</strong>
                  <span>Catalog information only</span>
                </div>

                <div class="data-cell" data-label="Status">
                  <span class="status-pill" [class]="statusClass(item.status)">
                    {{ statusLabel(item.status) }}
                  </span>
                </div>

                <div class="data-cell" data-label="Updated">
                  <strong>{{ item.updatedAt | date:'mediumDate' }}</strong>
                  <span>{{ item.updatedAt | date:'shortTime' }}</span>
                </div>

                <div class="row-actions">
                  @if (item.status === 'DRAFT') {
                    <a
                      [routerLink]="['/seller/store/items', item.id, 'edit']"
                      [queryParams]="listQueryParams()"
                      class="row-action"
                    >
                      Edit
                    </a>
                    <button
                      type="button"
                      class="row-action primary"
                      (click)="publishItem(item)"
                      [disabled]="busyItemId() === item.id"
                    >
                      {{ busyItemId() === item.id ? 'Publishing' : 'Publish' }}
                    </button>
                  }
                  @if (item.status === 'ACTIVE') {
                    <a [routerLink]="['/listings', item.id]" class="row-action">View item</a>
                    <button
                      type="button"
                      class="row-action"
                      (click)="pauseItem(item)"
                      [disabled]="busyItemId() === item.id"
                    >
                      {{ busyItemId() === item.id ? 'Pausing' : 'Pause' }}
                    </button>
                  }
                  @if (item.status === 'PAUSED') {
                    <a
                      [routerLink]="['/seller/store/items', item.id, 'edit']"
                      [queryParams]="listQueryParams()"
                      class="row-action"
                    >
                      Edit
                    </a>
                    <button
                      type="button"
                      class="row-action primary"
                      (click)="relistItem(item)"
                      [disabled]="busyItemId() === item.id"
                    >
                      {{ busyItemId() === item.id ? 'Relisting' : 'Relist' }}
                    </button>
                  }
                </div>
              </article>
            }
          </section>

          @if (hasMore()) {
            <div class="load-more-row">
              <button
                type="button"
                class="secondary-action"
                (click)="loadMore()"
                [disabled]="loadingMore()"
              >
                {{ loadingMore() ? 'Loading' : 'Load more' }}
              </button>
            </div>
          }
        }
      }
    </section>
  `,
  styles: [`
    :host {
      display: block;
      --catalog-surface: var(--color-bg-secondary);
      --catalog-surface-soft: var(--color-bg-tertiary);
      --catalog-border: var(--color-border);
      --catalog-text: var(--color-text-primary);
      --catalog-muted: var(--color-text-muted);
      --catalog-accent: var(--color-accent);
    }

    .catalog-page {
      display: flex;
      flex-direction: column;
      gap: 1rem;
      max-width: 1180px;
      margin: 0 auto;
    }

    .page-header {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 1rem;
      padding: 1.25rem;
      border: 1px solid var(--catalog-border);
      border-radius: var(--radius-lg);
      background: var(--catalog-surface);
    }

    .header-copy {
      min-width: 0;
    }

    .eyebrow,
    h1,
    h2,
    p {
      margin: 0;
    }

    .eyebrow {
      margin-bottom: 0.35rem;
      color: var(--catalog-accent);
      font-size: 0.75rem;
      font-weight: 850;
      letter-spacing: 0.08em;
      text-transform: uppercase;
    }

    h1 {
      color: var(--catalog-text);
      font-size: 1.75rem;
      letter-spacing: 0;
      overflow-wrap: anywhere;
    }

    .header-copy > p:last-child {
      margin-top: 0.35rem;
      color: var(--catalog-muted);
      font-size: 0.875rem;
      font-weight: 650;
    }

    .primary-action,
    .secondary-action,
    .search-action,
    .clear-action,
    .row-action {
      min-height: 40px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      padding: 0 0.875rem;
      border-radius: var(--radius-md);
      font: inherit;
      font-size: 0.875rem;
      font-weight: 800;
      text-decoration: none;
      white-space: nowrap;
      cursor: pointer;
    }

    .primary-action,
    .search-action,
    .row-action.primary {
      border: 1px solid transparent;
      background: var(--catalog-accent);
      color: var(--color-accent-contrast);
    }

    .secondary-action,
    .clear-action,
    .row-action {
      border: 1px solid var(--catalog-border);
      background: var(--catalog-surface);
      color: var(--catalog-text);
    }

    button:disabled {
      cursor: not-allowed;
      opacity: 0.55;
    }

    a:focus-visible,
    button:focus-visible,
    input:focus-visible {
      outline: 3px solid color-mix(in srgb, var(--catalog-accent) 35%, transparent);
      outline-offset: 2px;
    }

    .status-rail {
      display: grid;
      grid-template-columns: repeat(4, minmax(0, 1fr));
      overflow: hidden;
      border: 1px solid var(--catalog-border);
      border-radius: var(--radius-lg);
      background: var(--catalog-surface);
    }

    .status-option {
      min-height: 66px;
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 0.65rem;
      padding: 0.75rem 1rem;
      border: 0;
      border-right: 1px solid var(--catalog-border);
      background: transparent;
      color: var(--catalog-muted);
      font: inherit;
      font-size: 0.8125rem;
      font-weight: 800;
      cursor: pointer;
    }

    .status-option:last-child {
      border-right: 0;
    }

    .status-option strong {
      min-width: 30px;
      min-height: 30px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      border-radius: 50%;
      background: var(--catalog-surface-soft);
      color: var(--catalog-text);
      font-size: 0.8125rem;
    }

    .status-option.selected {
      box-shadow: inset 0 -3px 0 var(--catalog-accent);
      color: var(--catalog-text);
      background: color-mix(in srgb, var(--catalog-accent) 7%, var(--catalog-surface));
    }

    .status-option.selected strong {
      background: var(--catalog-accent);
      color: var(--color-accent-contrast);
    }

    .catalog-tools {
      display: flex;
      align-items: flex-end;
      justify-content: space-between;
      gap: 1rem;
      padding: 1rem;
      border: 1px solid var(--catalog-border);
      border-radius: var(--radius-lg);
      background: var(--catalog-surface);
    }

    .search-form {
      width: min(100%, 620px);
    }

    .search-form label {
      display: block;
      margin-bottom: 0.4rem;
      color: var(--catalog-text);
      font-size: 0.75rem;
      font-weight: 800;
    }

    .search-controls {
      display: grid;
      grid-template-columns: minmax(0, 1fr) auto auto;
      gap: 0.5rem;
    }

    .search-controls input {
      min-width: 0;
      min-height: 42px;
      padding: 0 0.8rem;
      border: 1px solid var(--catalog-border);
      border-radius: var(--radius-md);
      background: var(--color-bg-primary);
      color: var(--catalog-text);
      font: inherit;
      font-size: 0.875rem;
    }

    .result-count {
      flex: 0 0 auto;
      color: var(--catalog-text);
      font-size: 0.8125rem;
      font-weight: 800;
    }

    .result-count span {
      color: var(--catalog-muted);
      font-weight: 650;
    }

    .message {
      padding: 0.75rem 1rem;
      border: 1px solid var(--catalog-border);
      border-radius: var(--radius-md);
      background: var(--catalog-surface);
      font-size: 0.875rem;
      font-weight: 750;
    }

    .message.error {
      border-color: color-mix(in srgb, var(--color-danger) 35%, var(--catalog-border));
      color: var(--color-danger);
      background: color-mix(in srgb, var(--color-danger) 8%, var(--catalog-surface));
    }

    .message.success {
      border-color: color-mix(in srgb, #168064 35%, var(--catalog-border));
      color: #126951;
      background: color-mix(in srgb, #168064 8%, var(--catalog-surface));
    }

    .catalog-list {
      overflow: hidden;
      border: 1px solid var(--catalog-border);
      border-radius: var(--radius-lg);
      background: var(--catalog-surface);
    }

    .catalog-columns,
    .catalog-row {
      display: grid;
      grid-template-columns:
        minmax(250px, 2fr)
        minmax(96px, 0.7fr)
        minmax(90px, 0.6fr)
        minmax(100px, 0.7fr)
        minmax(116px, 0.8fr)
        minmax(200px, 1.25fr);
      gap: 0.75rem;
      align-items: center;
    }

    .catalog-columns {
      min-height: 42px;
      padding: 0 1rem;
      border-bottom: 1px solid var(--catalog-border);
      background: var(--catalog-surface-soft);
      color: var(--catalog-muted);
      font-size: 0.6875rem;
      font-weight: 850;
      text-transform: uppercase;
      letter-spacing: 0.06em;
    }

    .catalog-columns span:last-child {
      text-align: right;
    }

    .catalog-row {
      min-height: 96px;
      padding: 0.8rem 1rem;
      border-bottom: 1px solid var(--catalog-border);
    }

    .catalog-row:last-child {
      border-bottom: 0;
    }

    .catalog-row:hover {
      background: color-mix(in srgb, var(--catalog-accent) 3%, transparent);
    }

    .item-cell {
      min-width: 0;
      display: flex;
      align-items: center;
      gap: 0.75rem;
    }

    .item-image {
      width: 64px;
      aspect-ratio: 1;
      flex: 0 0 64px;
      display: flex;
      align-items: center;
      justify-content: center;
      overflow: hidden;
      border: 1px solid var(--catalog-border);
      border-radius: var(--radius-md);
      background: var(--catalog-surface-soft);
      color: var(--catalog-muted);
      font-size: 0.625rem;
      font-weight: 750;
      text-align: center;
    }

    .item-image img {
      width: 100%;
      height: 100%;
      display: block;
      object-fit: cover;
    }

    .item-copy {
      min-width: 0;
      display: flex;
      flex-direction: column;
      gap: 0.2rem;
    }

    .item-title {
      color: var(--catalog-text);
      font-size: 0.925rem;
      font-weight: 850;
      line-height: 1.25;
      overflow-wrap: anywhere;
      text-decoration: none;
    }

    a.item-title:hover {
      color: var(--catalog-accent);
      text-decoration: underline;
      text-underline-offset: 3px;
    }

    .sku {
      color: var(--catalog-muted);
      font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
      font-size: 0.75rem;
      overflow-wrap: anywhere;
    }

    .condition {
      color: var(--catalog-muted);
      font-size: 0.75rem;
      text-transform: capitalize;
    }

    .data-cell {
      min-width: 0;
      display: flex;
      flex-direction: column;
      gap: 0.2rem;
      color: var(--catalog-text);
      font-size: 0.8125rem;
    }

    .data-cell strong {
      overflow-wrap: anywhere;
    }

    .data-cell > span:not(.status-pill) {
      color: var(--catalog-muted);
      font-size: 0.6875rem;
      font-weight: 650;
    }

    .moderation-note {
      max-width: 48rem;
      margin-top: 0.25rem;
      padding: 0.45rem 0.55rem;
      border-left: 3px solid var(--color-danger);
      background: rgba(244, 63, 94, 0.08);
      color: var(--color-danger);
      font-size: 0.75rem;
      font-weight: 750;
      line-height: 1.4;
      overflow-wrap: anywhere;
    }

    .status-pill {
      width: fit-content;
      min-height: 28px;
      display: inline-flex;
      align-items: center;
      padding: 0 0.6rem;
      border: 1px solid var(--catalog-border);
      border-radius: 999px;
      background: var(--catalog-surface-soft);
      color: var(--catalog-text);
      font-size: 0.6875rem;
      font-weight: 850;
      text-transform: uppercase;
    }

    .status-pill.status-active {
      border-color: rgba(22, 128, 100, 0.28);
      background: rgba(22, 128, 100, 0.1);
      color: #126951;
    }

    .status-pill.status-draft {
      border-color: color-mix(in srgb, var(--catalog-accent) 28%, var(--catalog-border));
      background: color-mix(in srgb, var(--catalog-accent) 8%, var(--catalog-surface));
      color: var(--catalog-accent);
    }

    .status-pill.status-removed-by-admin {
      border-color: rgba(244, 63, 94, 0.28);
      background: rgba(244, 63, 94, 0.1);
      color: #b4234f;
    }

    .status-pill.status-paused {
      border-color: rgba(188, 115, 20, 0.28);
      background: rgba(188, 115, 20, 0.1);
      color: #8b570f;
    }

    .row-actions {
      display: flex;
      align-items: center;
      justify-content: flex-end;
      gap: 0.45rem;
    }

    .row-action {
      min-height: 36px;
      padding: 0 0.7rem;
      font-size: 0.75rem;
    }

    .load-more-row {
      display: flex;
      justify-content: center;
      padding: 0.25rem 0;
    }

    .catalog-loading {
      min-height: 180px;
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: 0.75rem;
      align-items: stretch;
      padding: 1rem;
      border: 1px solid var(--catalog-border);
      border-radius: var(--radius-lg);
      background: var(--catalog-surface);
    }

    .catalog-loading span {
      border-radius: var(--radius-md);
      background: var(--catalog-surface-soft);
      animation: catalog-pulse 1.2s ease-in-out infinite alternate;
    }

    .catalog-loading p {
      grid-column: 1 / -1;
      color: var(--catalog-muted);
      font-size: 0.8125rem;
      text-align: center;
    }

    @keyframes catalog-pulse {
      from { opacity: 0.5; }
      to { opacity: 1; }
    }

    @media (prefers-reduced-motion: reduce) {
      .catalog-loading span {
        animation: none;
      }
    }

    @media (max-width: 960px) {
      .catalog-columns,
      .catalog-row {
        grid-template-columns:
          minmax(230px, 2fr)
          minmax(90px, 0.8fr)
          minmax(90px, 0.7fr)
          minmax(110px, 0.9fr)
          minmax(190px, 1.4fr);
      }

      .catalog-columns span:nth-child(3),
      .catalog-row > .data-cell:nth-child(3) {
        display: none;
      }
    }

    @media (max-width: 760px) {
      .page-header,
      .catalog-tools {
        align-items: stretch;
        flex-direction: column;
      }

      .page-header .primary-action {
        width: 100%;
      }

      .status-rail {
        grid-template-columns: repeat(2, minmax(0, 1fr));
      }

      .status-option:nth-child(2) {
        border-right: 0;
      }

      .status-option:nth-child(-n + 2) {
        border-bottom: 1px solid var(--catalog-border);
      }

      .search-controls {
        grid-template-columns: minmax(0, 1fr) auto;
      }

      .search-controls input {
        grid-column: 1 / -1;
      }

      .result-count {
        align-self: flex-start;
      }

      .catalog-columns {
        display: none;
      }

      .catalog-row {
        display: grid;
        grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
        gap: 0.85rem 1rem;
        padding: 1rem;
      }

      .item-cell,
      .row-actions {
        grid-column: 1 / -1;
      }

      .catalog-row > .data-cell:nth-child(3) {
        display: flex;
      }

      .data-cell::before {
        content: attr(data-label);
        color: var(--catalog-muted);
        font-size: 0.625rem;
        font-weight: 850;
        text-transform: uppercase;
      }

      .row-actions {
        justify-content: stretch;
      }

      .row-action {
        flex: 1 1 0;
      }
    }

    @media (max-width: 440px) {
      .catalog-row {
        grid-template-columns: 1fr;
      }

      .data-cell,
      .item-cell,
      .row-actions {
        grid-column: 1;
      }

      .item-image {
        width: 56px;
        flex-basis: 56px;
      }
    }
  `],
})
export class BusinessStoreItemsComponent implements OnInit {
  private readonly businessStoreService = inject(BusinessStoreService);
  private readonly listingService = inject(ListingService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  readonly statusOptions: ReadonlyArray<{ value: CatalogStatusFilter; label: string }> = [
    { value: 'ALL', label: 'All' },
    { value: 'DRAFT', label: 'Draft' },
    { value: 'ACTIVE', label: 'Active' },
    { value: 'PAUSED', label: 'Paused' },
    { value: 'REMOVED_BY_ADMIN', label: 'Removed' },
  ];
  readonly loadingContext = signal(true);
  readonly loadingItems = signal(false);
  readonly loadingMore = signal(false);
  readonly errorMsg = signal('');
  readonly successMsg = signal('');
  readonly context = signal<BusinessStoreContext | null>(null);
  readonly items = signal<ListingDraft[]>([]);
  readonly summary = signal<BusinessStoreItemStatusSummary>({
    total: 0,
    draft: 0,
    active: 0,
    paused: 0,
    removed: 0,
  });
  readonly busyItemId = signal('');
  readonly nextCursor = signal<string | null>(null);
  readonly hasMore = signal(false);

  searchTerm = '';
  selectedStatus: CatalogStatusFilter = 'ALL';
  private filtersInitialized = false;

  ngOnInit(): void {
    this.route.queryParamMap.subscribe(params => {
      this.searchTerm = params.get('q') || '';
      this.selectedStatus = this.normalizedStatus(params.get('status'));
      this.filtersInitialized = true;
      if (this.context()) {
        this.loadItems();
      }
    });

    this.businessStoreService.getCurrentStoreContext().subscribe({
      next: context => {
        this.context.set(context);
        this.loadingContext.set(false);
        if (context && this.filtersInitialized) {
          this.loadItems();
        }
      },
      error: () => {
        this.loadingContext.set(false);
        this.errorMsg.set('Business store context could not be loaded.');
      },
    });
  }

  applySearch(): void {
    this.updateFilterUrl();
  }

  clearSearch(): void {
    this.searchTerm = '';
    this.updateFilterUrl();
  }

  clearFilters(): void {
    this.searchTerm = '';
    this.selectedStatus = 'ALL';
    this.updateFilterUrl();
  }

  selectStatus(status: CatalogStatusFilter): void {
    if (this.selectedStatus === status) {
      return;
    }
    this.selectedStatus = status;
    this.updateFilterUrl();
  }

  loadMore(): void {
    const context = this.context();
    const cursor = this.nextCursor();
    if (!context || !cursor || this.loadingItems() || this.loadingMore()) {
      return;
    }
    this.loadingMore.set(true);
    this.errorMsg.set('');
    this.listingService.searchBusinessStoreItems(
      context.businessId,
      this.searchParams(cursor),
    ).subscribe({
      next: response => {
        this.items.set([...this.items(), ...response.data]);
        this.summary.set(response.summary);
        this.nextCursor.set(response.page.nextCursor);
        this.hasMore.set(response.page.hasMore);
        this.loadingMore.set(false);
      },
      error: error => {
        this.errorMsg.set(error.error?.error?.message || 'More catalog items could not be loaded.');
        this.loadingMore.set(false);
      },
    });
  }

  statusCount(status: CatalogStatusFilter): number {
    const counts = this.summary();
    if (status === 'ALL') {
      return counts.total;
    }
    return status === 'REMOVED_BY_ADMIN' ? counts.removed : counts[status.toLowerCase() as 'draft' | 'active' | 'paused'];
  }

  conditionLabel(condition: string): string {
    return condition.replace(/_/g, ' ').toLowerCase();
  }

  statusLabel(status: string): string {
    return status.replace(/_/g, ' ').toLowerCase();
  }

  statusClass(status: string): string {
    const normalized = status.toLowerCase().replace(/_/g, '-');
    return `status-pill status-${normalized}`;
  }

  imageUrl(item: ListingDraft): string {
    const image = item.images?.[0];
    return this.listingService.mediaUrl(image?.url || image?.uploadUrl || '');
  }

  imageAlt(item: ListingDraft): string {
    const image = item.images?.[0];
    return image?.altText || image?.originalFileName || `${item.title} image`;
  }

  listQueryParams(): Record<string, string | null> {
    return {
      q: this.searchTerm.trim() || null,
      status: this.selectedStatus === 'ALL' ? null : this.selectedStatus,
    };
  }

  publishItem(item: ListingDraft): void {
    const context = this.context();
    if (!context) {
      return;
    }
    this.runItemAction(
      item,
      this.listingService.publishBusinessStoreItem(context.businessId, item.id, item.version),
      'Store item could not be published.',
      'Store item published.',
    );
  }

  pauseItem(item: ListingDraft): void {
    const context = this.context();
    if (!context) {
      return;
    }
    this.runItemAction(
      item,
      this.listingService.pauseBusinessStoreItem(context.businessId, item.id, item.version),
      'Store item could not be paused.',
      'Store item paused.',
    );
  }

  relistItem(item: ListingDraft): void {
    const context = this.context();
    if (!context) {
      return;
    }
    this.runItemAction(
      item,
      this.listingService.relistBusinessStoreItem(context.businessId, item.id, item.version),
      'Store item could not be relisted.',
      'Store item relisted.',
    );
  }

  private loadItems(): void {
    const context = this.context();
    if (!context) {
      return;
    }
    this.loadingItems.set(true);
    this.errorMsg.set('');
    this.successMsg.set('');
    this.nextCursor.set(null);
    this.hasMore.set(false);
    this.listingService.searchBusinessStoreItems(
      context.businessId,
      this.searchParams(),
    ).subscribe({
      next: response => {
        this.items.set(response.data);
        this.summary.set(response.summary);
        this.nextCursor.set(response.page.nextCursor);
        this.hasMore.set(response.page.hasMore);
        this.loadingItems.set(false);
      },
      error: error => {
        this.items.set([]);
        this.errorMsg.set(error.error?.error?.message || 'Business catalog items could not be loaded.');
        this.loadingItems.set(false);
      },
    });
  }

  private runItemAction(
    previous: ListingDraft,
    action: ReturnType<ListingService['publishBusinessStoreItem']>,
    fallbackMessage: string,
    successMessage: string,
  ): void {
    this.busyItemId.set(previous.id);
    this.errorMsg.set('');
    this.successMsg.set('');
    action.subscribe({
      next: updated => {
        this.applyUpdatedItem(previous, updated);
        this.successMsg.set(successMessage);
        this.busyItemId.set('');
      },
      error: error => {
        this.errorMsg.set(error.error?.error?.message || fallbackMessage);
        this.busyItemId.set('');
      },
    });
  }

  // Updates one row and lifecycle counts without reloading the whole management page.
  private applyUpdatedItem(previous: ListingDraft, updated: ListingDraft): void {
    const remainsVisible = this.selectedStatus === 'ALL' || this.selectedStatus === updated.status;
    this.items.update(items => remainsVisible
      ? items.map(item => item.id === updated.id ? updated : item)
      : items.filter(item => item.id !== updated.id));
    if (previous.status === updated.status) {
      return;
    }

    this.summary.update(summary => {
      const next = { ...summary };
      this.adjustStatusCount(next, previous.status, -1);
      this.adjustStatusCount(next, updated.status, 1);
      return next;
    });
  }

  private adjustStatusCount(
    summary: BusinessStoreItemStatusSummary,
    status: string,
    delta: number,
  ): void {
    if (status === 'DRAFT') {
      summary.draft = Math.max(0, summary.draft + delta);
    } else if (status === 'ACTIVE') {
      summary.active = Math.max(0, summary.active + delta);
    } else if (status === 'PAUSED') {
      summary.paused = Math.max(0, summary.paused + delta);
    } else if (status === 'REMOVED_BY_ADMIN') {
      summary.removed = Math.max(0, summary.removed + delta);
    }
  }

  private searchParams(cursor?: string): BusinessStoreItemSearchParams {
    return {
      q: this.searchTerm.trim() || null,
      status: this.selectedStatus === 'ALL' ? null : this.selectedStatus,
      cursor: cursor || null,
      limit: 24,
    };
  }

  private updateFilterUrl(): void {
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: this.listQueryParams(),
    });
  }

  private normalizedStatus(value: string | null): CatalogStatusFilter {
    return value === 'DRAFT' || value === 'ACTIVE' || value === 'PAUSED' ? value : 'ALL';
  }
}
