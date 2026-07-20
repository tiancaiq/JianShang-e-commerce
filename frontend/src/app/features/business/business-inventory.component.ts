import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { BusinessStoreContext } from '../../core/models/business-store.model';
import {
  InventoryCatalogItem,
  InventoryMovement,
  InventoryOperation,
  InventoryReason,
} from '../../core/models/inventory.model';
import { BusinessStoreService } from '../../core/services/business-store.service';
import { InventoryService } from '../../core/services/inventory.service';
import { EmptyStateComponent } from '../../shared/components/ui/empty-state.component';

type InventoryEditorMode = 'INITIALIZE' | 'ADJUST';

@Component({
  selector: 'app-business-inventory',
  standalone: true,
  imports: [DatePipe, EmptyStateComponent, FormsModule, RouterLink],
  template: `
    <section class="inventory-page">
      <header class="page-header">
        <div>
          <p class="eyebrow">Commerce operations</p>
          <h1>Inventory</h1>
          <p>Control sellable stock for active and paused business items.</p>
        </div>
        <a routerLink="/seller/store/items" class="secondary-action">Store items</a>
      </header>

      @if (loadingContext()) {
        <app-ui-empty-state>Loading your business inventory.</app-ui-empty-state>
      } @else if (!context()) {
        <app-ui-empty-state>
          <h2>No approved business store</h2>
          <p>An active business store is required before inventory can be managed.</p>
          <a routerLink="/seller/business/apply" class="primary-action">Business application</a>
        </app-ui-empty-state>
      } @else {
        <section class="tools" aria-label="Inventory filters">
          <form class="search-form" (ngSubmit)="loadItems()">
            <label for="inventory-search">Search inventory</label>
            <div class="search-controls">
              <input
                id="inventory-search"
                name="inventorySearch"
                [(ngModel)]="searchTerm"
                placeholder="Item title or SKU"
                maxlength="120"
              />
              <select name="listingStatus" [(ngModel)]="listingStatus" aria-label="Listing status">
                <option value="ALL">All statuses</option>
                <option value="ACTIVE">Active</option>
                <option value="PAUSED">Paused</option>
                <option value="DRAFT">Draft</option>
              </select>
              <button type="submit" class="primary-action">Search</button>
            </div>
          </form>
          <div class="summary" aria-live="polite">
            <strong>{{ initializedCount() }}</strong>
            <span>of {{ items().length }} shown initialized</span>
          </div>
        </section>

        @if (errorMsg()) {
          <div class="message error" role="alert">{{ errorMsg() }}</div>
        }
        @if (successMsg()) {
          <div class="message success" role="status">{{ successMsg() }}</div>
        }

        @if (editorItem()) {
          <section class="editor" aria-labelledby="inventory-editor-title">
            <div class="editor-heading">
              <div>
                <p class="eyebrow">{{ editorMode() === 'INITIALIZE' ? 'Opening balance' : 'Stock adjustment' }}</p>
                <h2 id="inventory-editor-title">{{ editorItem()?.title }}</h2>
                <p>{{ editorItem()?.sku }}</p>
              </div>
              <button type="button" class="icon-action" aria-label="Close inventory editor" (click)="closeEditor()">×</button>
            </div>

            @if (editorMode() === 'ADJUST') {
              <div class="balance-strip">
                <span><small>On hand</small><strong>{{ editorItem()?.inventory?.onHand }}</strong></span>
                <span><small>Reserved</small><strong>{{ editorItem()?.inventory?.reserved }}</strong></span>
                <span><small>Available</small><strong>{{ editorItem()?.inventory?.available }}</strong></span>
                <span><small>After change</small><strong>{{ projectedOnHand() }}</strong></span>
              </div>
            }

            <form class="editor-form" (ngSubmit)="saveInventory()">
              @if (editorMode() === 'ADJUST') {
                <fieldset class="mode-control">
                  <legend>Adjustment type</legend>
                  <button
                    type="button"
                    [class.selected]="operation === 'ADJUST'"
                    [attr.aria-pressed]="operation === 'ADJUST'"
                    (click)="operation = 'ADJUST'"
                  >
                    Add or subtract
                  </button>
                  <button
                    type="button"
                    [class.selected]="operation === 'SET'"
                    [attr.aria-pressed]="operation === 'SET'"
                    (click)="operation = 'SET'"
                  >
                    Set exact count
                  </button>
                </fieldset>
              }

              <label>
                <span>{{ editorMode() === 'INITIALIZE' || operation === 'SET' ? 'On-hand quantity' : 'Quantity change' }}</span>
                <input
                  name="quantity"
                  type="number"
                  [min]="editorMode() === 'INITIALIZE' || operation === 'SET' ? 0 : null"
                  step="1"
                  required
                  [(ngModel)]="quantity"
                />
              </label>

              @if (editorMode() === 'ADJUST') {
                <label>
                  <span>Reason</span>
                  <select name="reason" [(ngModel)]="reason" required>
                    @for (option of reasonOptions; track option.value) {
                      <option [value]="option.value">{{ option.label }}</option>
                    }
                  </select>
                </label>
              }

              <label class="note-field">
                <span>Note <small>Optional</small></span>
                <input name="note" maxlength="500" [(ngModel)]="note" placeholder="Receiving reference or correction detail" />
              </label>

              <div class="editor-actions">
                <button type="button" class="secondary-action" (click)="closeEditor()">Cancel</button>
                <button type="submit" class="primary-action" [disabled]="saving() || !canSave()">
                  {{ saving() ? 'Saving' : editorMode() === 'INITIALIZE' ? 'Initialize inventory' : 'Save adjustment' }}
                </button>
              </div>
            </form>
          </section>
        }

        @if (historyItem()) {
          <section class="history" aria-labelledby="movement-history-title">
            <div class="editor-heading">
              <div>
                <p class="eyebrow">Audit ledger</p>
                <h2 id="movement-history-title">{{ historyItem()?.title }}</h2>
                <p>Inventory movement history</p>
              </div>
              <button type="button" class="icon-action" aria-label="Close movement history" (click)="closeHistory()">×</button>
            </div>
            @if (loadingHistory()) {
              <p class="history-loading">Loading movement history.</p>
            } @else if (movements().length === 0) {
              <p class="history-loading">No movements recorded.</p>
            } @else {
              <div class="movement-list">
                @for (movement of movements(); track movement.id) {
                  <article class="movement-row">
                    <div>
                      <strong>{{ reasonLabel(movement.reason) }}</strong>
                      <span>
                        {{
                          movement.operation === 'SET'
                            ? 'Set count'
                            : movement.reason === 'INITIAL_STOCK'
                              ? 'Opening balance'
                              : 'Adjusted count'
                        }}
                      </span>
                    </div>
                    <strong [class.positive]="movement.quantityDelta > 0">
                      {{ movement.quantityDelta > 0 ? '+' : '' }}{{ movement.quantityDelta }}
                    </strong>
                    <span>{{ movement.onHandBefore }} → {{ movement.onHandAfter }}</span>
                    <span>{{ movement.createdAt | date:'medium' }}</span>
                    <span>{{ movement.note || 'No note' }}</span>
                  </article>
                }
              </div>
            }
          </section>
        }

        @if (loadingItems() && items().length === 0) {
          <app-ui-empty-state>Loading inventory items.</app-ui-empty-state>
        } @else if (items().length === 0) {
          <app-ui-empty-state>
            <h2>No matching business items</h2>
            <p>Create or publish a business item before initializing stock.</p>
            <a routerLink="/seller/store/items/new" class="primary-action">New item</a>
          </app-ui-empty-state>
        } @else {
          <section class="inventory-table" aria-label="Business inventory">
            <div class="table-heading" aria-hidden="true">
              <span>Item</span>
              <span>Listing</span>
              <span>On hand</span>
              <span>Reserved</span>
              <span>Available</span>
              <span>Updated</span>
              <span>Actions</span>
            </div>
            @for (item of items(); track item.listingId) {
              <article class="inventory-row">
                <div class="item-cell">
                  <strong>{{ item.title }}</strong>
                  <span>{{ item.sku }}</span>
                </div>
                <div data-label="Listing">
                  <span class="status-pill" [class.active]="item.listingStatus === 'ACTIVE'">
                    {{ item.listingStatus.toLowerCase() }}
                  </span>
                </div>
                @if (item.inventory) {
                  <strong data-label="On hand">{{ item.inventory.onHand }}</strong>
                  <span data-label="Reserved">{{ item.inventory.reserved }}</span>
                  <strong data-label="Available">{{ item.inventory.available }}</strong>
                  <span data-label="Updated">{{ item.inventory.updatedAt | date:'mediumDate' }}</span>
                  <div class="row-actions">
                    <button type="button" (click)="openAdjust(item)">Adjust</button>
                    <button type="button" (click)="openHistory(item)">History</button>
                  </div>
                } @else {
                  <span class="not-initialized">Not initialized</span>
                  <span aria-hidden="true">—</span>
                  <span aria-hidden="true">—</span>
                  <span aria-hidden="true">—</span>
                  <div class="row-actions">
                    @if (canInitialize(item)) {
                      <button type="button" class="primary-row-action" (click)="openInitialize(item)">Initialize</button>
                    } @else {
                      <span>Activate item first</span>
                    }
                  </div>
                }
              </article>
            }
          </section>

          @if (hasMore()) {
            <div class="load-more-row">
              <button type="button" class="secondary-action" (click)="loadMore()" [disabled]="loadingMore()">
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
      --surface: var(--color-bg-secondary);
      --surface-soft: var(--color-bg-tertiary);
      --border: var(--color-border);
      --text: var(--color-text-primary);
      --muted: var(--color-text-muted);
      --accent: var(--color-accent);
    }

    .inventory-page { display: flex; flex-direction: column; gap: 1rem; max-width: 1180px; margin: 0 auto; }
    .page-header, .tools, .editor, .history, .inventory-table {
      border: 1px solid var(--border);
      border-radius: var(--radius-lg);
      background: var(--surface);
    }
    .page-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 1rem; padding: 1.25rem; }
    h1, h2, p { margin: 0; }
    h1 { color: var(--text); font-size: 1.75rem; letter-spacing: 0; }
    h2 { color: var(--text); font-size: 1.125rem; letter-spacing: 0; overflow-wrap: anywhere; }
    .page-header > div > p:last-child, .editor-heading p:last-child { margin-top: 0.3rem; color: var(--muted); font-size: 0.8125rem; }
    .eyebrow { margin-bottom: 0.3rem; color: var(--accent); font-size: 0.7rem; font-weight: 850; letter-spacing: 0.08em; text-transform: uppercase; }

    .primary-action, .secondary-action, .row-actions button, .icon-action {
      min-height: 40px; display: inline-flex; align-items: center; justify-content: center;
      padding: 0 0.875rem; border-radius: var(--radius-md); font: inherit; font-size: 0.8125rem;
      font-weight: 800; text-decoration: none; cursor: pointer;
    }
    .primary-action, .primary-row-action { border: 1px solid transparent; background: var(--accent); color: var(--color-accent-contrast); }
    .secondary-action, .row-actions button, .icon-action { border: 1px solid var(--border); background: var(--surface); color: var(--text); }
    button:disabled { cursor: not-allowed; opacity: 0.55; }

    .tools { display: flex; align-items: flex-end; justify-content: space-between; gap: 1rem; padding: 1rem; }
    .search-form { flex: 1; max-width: 760px; }
    label > span, .search-form > label { display: block; margin-bottom: 0.35rem; color: var(--text); font-size: 0.75rem; font-weight: 800; }
    .search-controls { display: grid; grid-template-columns: minmax(0, 1fr) 150px auto; gap: 0.5rem; }
    input, select {
      width: 100%; min-width: 0; min-height: 42px; padding: 0 0.75rem; border: 1px solid var(--border);
      border-radius: var(--radius-md); background: var(--color-bg-primary); color: var(--text); font: inherit; font-size: 0.875rem;
    }
    input:focus-visible, select:focus-visible, button:focus-visible, a:focus-visible {
      outline: 3px solid color-mix(in srgb, var(--accent) 35%, transparent); outline-offset: 2px;
    }
    .summary { display: flex; align-items: baseline; gap: 0.35rem; color: var(--muted); font-size: 0.75rem; white-space: nowrap; }
    .summary strong { color: var(--text); font-size: 1.2rem; }

    .message { padding: 0.75rem 1rem; border: 1px solid var(--border); border-radius: var(--radius-md); font-size: 0.875rem; font-weight: 750; }
    .message.error { border-color: color-mix(in srgb, var(--color-danger) 35%, var(--border)); background: color-mix(in srgb, var(--color-danger) 8%, var(--surface)); color: var(--color-danger); }
    .message.success { border-color: rgba(22, 128, 100, 0.35); background: rgba(22, 128, 100, 0.08); color: #32a884; }

    .editor, .history { padding: 1rem; }
    .editor-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 1rem; }
    .icon-action { width: 40px; padding: 0; font-size: 1.35rem; line-height: 1; }
    .balance-strip { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); margin-top: 1rem; border: 1px solid var(--border); border-radius: var(--radius-md); overflow: hidden; }
    .balance-strip span { display: flex; flex-direction: column; gap: 0.25rem; padding: 0.75rem; border-right: 1px solid var(--border); background: var(--surface-soft); }
    .balance-strip span:last-child { border-right: 0; }
    .balance-strip small { color: var(--muted); font-size: 0.6875rem; font-weight: 750; text-transform: uppercase; }
    .balance-strip strong { color: var(--text); font-size: 1.1rem; }

    .editor-form { display: grid; grid-template-columns: minmax(170px, 0.65fr) minmax(180px, 0.8fr) minmax(240px, 1.5fr) auto; gap: 0.75rem; align-items: end; margin-top: 1rem; }
    .mode-control { grid-column: 1 / -1; display: flex; gap: 0; padding: 0; border: 0; }
    .mode-control legend { margin-bottom: 0.35rem; color: var(--text); font-size: 0.75rem; font-weight: 800; }
    .mode-control button { min-height: 38px; padding: 0 0.8rem; border: 1px solid var(--border); background: var(--surface); color: var(--muted); font: inherit; font-size: 0.78rem; font-weight: 800; }
    .mode-control button:first-of-type { border-radius: var(--radius-md) 0 0 var(--radius-md); }
    .mode-control button:last-of-type { border-left: 0; border-radius: 0 var(--radius-md) var(--radius-md) 0; }
    .mode-control button.selected { background: var(--accent); color: var(--color-accent-contrast); }
    label small { color: var(--muted); font-weight: 650; }
    .editor-actions { display: flex; gap: 0.5rem; }

    .inventory-table { overflow: hidden; }
    .table-heading, .inventory-row {
      display: grid; grid-template-columns: minmax(210px, 1.7fr) minmax(88px, 0.65fr) repeat(3, minmax(72px, 0.55fr)) minmax(110px, 0.8fr) minmax(155px, 1.1fr);
      gap: 0.75rem; align-items: center;
    }
    .table-heading { min-height: 42px; padding: 0 1rem; border-bottom: 1px solid var(--border); background: var(--surface-soft); color: var(--muted); font-size: 0.65rem; font-weight: 850; text-transform: uppercase; }
    .table-heading span:last-child { text-align: right; }
    .inventory-row { min-height: 78px; padding: 0.75rem 1rem; border-bottom: 1px solid var(--border); color: var(--text); font-size: 0.8125rem; }
    .inventory-row:last-child { border-bottom: 0; }
    .item-cell { min-width: 0; display: flex; flex-direction: column; gap: 0.25rem; }
    .item-cell strong { overflow-wrap: anywhere; }
    .item-cell span { color: var(--muted); font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 0.72rem; overflow-wrap: anywhere; }
    .status-pill { display: inline-flex; width: fit-content; min-height: 26px; align-items: center; padding: 0 0.5rem; border: 1px solid var(--border); border-radius: 999px; background: var(--surface-soft); color: var(--muted); font-size: 0.65rem; font-weight: 850; text-transform: uppercase; }
    .status-pill.active { border-color: rgba(22, 128, 100, 0.3); background: rgba(22, 128, 100, 0.1); color: #32a884; }
    .not-initialized { grid-column: span 1; color: var(--muted); font-weight: 750; }
    .row-actions { display: flex; justify-content: flex-end; gap: 0.4rem; }
    .row-actions button { min-height: 34px; padding: 0 0.65rem; }
    .row-actions > span { color: var(--muted); font-size: 0.72rem; text-align: right; }

    .movement-list { margin-top: 1rem; border-top: 1px solid var(--border); }
    .movement-row { display: grid; grid-template-columns: minmax(160px, 1.2fr) 70px 100px 150px minmax(160px, 1.4fr); gap: 0.75rem; align-items: center; padding: 0.7rem 0; border-bottom: 1px solid var(--border); color: var(--muted); font-size: 0.75rem; }
    .movement-row > div { display: flex; flex-direction: column; gap: 0.2rem; }
    .movement-row strong { color: var(--text); }
    .movement-row > strong { color: var(--color-danger); }
    .movement-row > strong.positive { color: #32a884; }
    .history-loading { margin-top: 1rem; color: var(--muted); font-size: 0.8125rem; }
    .load-more-row { display: flex; justify-content: center; }

    @media (max-width: 900px) {
      .table-heading { display: none; }
      .inventory-row { grid-template-columns: repeat(3, minmax(0, 1fr)); }
      .item-cell, .row-actions { grid-column: 1 / -1; }
      .inventory-row > [data-label]::before { content: attr(data-label); display: block; margin-bottom: 0.2rem; color: var(--muted); font-size: 0.6rem; font-weight: 850; text-transform: uppercase; }
      .row-actions { justify-content: flex-start; }
      .editor-form { grid-template-columns: repeat(2, minmax(0, 1fr)); }
      .note-field, .editor-actions { grid-column: 1 / -1; }
      .movement-row { grid-template-columns: 1fr 70px 100px; }
      .movement-row > span:nth-last-child(-n + 2) { grid-column: span 1; }
    }
    @media (max-width: 620px) {
      .page-header, .tools { align-items: stretch; flex-direction: column; }
      .search-controls, .editor-form, .balance-strip, .inventory-row { grid-template-columns: 1fr; }
      .item-cell, .row-actions, .note-field, .editor-actions { grid-column: 1; }
      .balance-strip span { border-right: 0; border-bottom: 1px solid var(--border); }
      .balance-strip span:last-child { border-bottom: 0; }
      .movement-row { grid-template-columns: 1fr 70px; }
    }
  `],
})
export class BusinessInventoryComponent implements OnInit {
  private readonly businessStoreService = inject(BusinessStoreService);
  private readonly inventoryService = inject(InventoryService);

  readonly reasonOptions: ReadonlyArray<{ value: InventoryReason; label: string }> = [
    { value: 'RESTOCK', label: 'Stock received' },
    { value: 'STOCK_COUNT_CORRECTION', label: 'Stock correction' },
    { value: 'DAMAGED', label: 'Damaged' },
    { value: 'LOST', label: 'Lost' },
    { value: 'RETURNED', label: 'Returned' },
    { value: 'OTHER', label: 'Other' },
  ];
  readonly context = signal<BusinessStoreContext | null>(null);
  readonly items = signal<InventoryCatalogItem[]>([]);
  readonly movements = signal<InventoryMovement[]>([]);
  readonly editorItem = signal<InventoryCatalogItem | null>(null);
  readonly editorMode = signal<InventoryEditorMode | null>(null);
  readonly historyItem = signal<InventoryCatalogItem | null>(null);
  readonly loadingContext = signal(true);
  readonly loadingItems = signal(false);
  readonly loadingMore = signal(false);
  readonly loadingHistory = signal(false);
  readonly saving = signal(false);
  readonly hasMore = signal(false);
  readonly nextCursor = signal<string | null>(null);
  readonly errorMsg = signal('');
  readonly successMsg = signal('');

  searchTerm = '';
  listingStatus = 'ALL';
  operation: InventoryOperation = 'ADJUST';
  reason: InventoryReason = 'RESTOCK';
  quantity = 0;
  note = '';

  ngOnInit(): void {
    this.businessStoreService.getCurrentStoreContext().subscribe({
      next: context => {
        this.context.set(context);
        this.loadingContext.set(false);
        if (context) {
          this.loadItems();
        }
      },
      error: () => {
        this.loadingContext.set(false);
        this.errorMsg.set('Business store context could not be loaded.');
      },
    });
  }

  loadItems(): void {
    const context = this.context();
    if (!context) {
      return;
    }
    this.loadingItems.set(true);
    this.errorMsg.set('');
    this.successMsg.set('');
    this.inventoryService.list(context.businessId, {
      q: this.searchTerm.trim() || null,
      listingStatus: this.listingStatus === 'ALL' ? null : this.listingStatus,
      limit: 24,
    }).subscribe({
      next: page => {
        this.items.set(page.data);
        this.nextCursor.set(page.page.nextCursor);
        this.hasMore.set(page.page.hasMore);
        this.loadingItems.set(false);
      },
      error: error => {
        this.items.set([]);
        this.errorMsg.set(this.errorMessage(error, 'Inventory could not be loaded.'));
        this.loadingItems.set(false);
      },
    });
  }

  loadMore(): void {
    const context = this.context();
    const cursor = this.nextCursor();
    if (!context || !cursor || this.loadingMore()) {
      return;
    }
    this.loadingMore.set(true);
    this.inventoryService.list(context.businessId, {
      q: this.searchTerm.trim() || null,
      listingStatus: this.listingStatus === 'ALL' ? null : this.listingStatus,
      cursor,
      limit: 24,
    }).subscribe({
      next: page => {
        this.items.set([...this.items(), ...page.data]);
        this.nextCursor.set(page.page.nextCursor);
        this.hasMore.set(page.page.hasMore);
        this.loadingMore.set(false);
      },
      error: error => {
        this.errorMsg.set(this.errorMessage(error, 'More inventory items could not be loaded.'));
        this.loadingMore.set(false);
      },
    });
  }

  initializedCount(): number {
    return this.items().filter(item => item.inventory !== null).length;
  }

  canInitialize(item: InventoryCatalogItem): boolean {
    return item.listingStatus === 'ACTIVE' || item.listingStatus === 'PAUSED';
  }

  openInitialize(item: InventoryCatalogItem): void {
    this.editorItem.set(item);
    this.editorMode.set('INITIALIZE');
    this.quantity = Math.max(0, item.catalogQuantitySuggestion);
    this.note = '';
    this.errorMsg.set('');
    this.successMsg.set('');
  }

  openAdjust(item: InventoryCatalogItem): void {
    this.editorItem.set(item);
    this.editorMode.set('ADJUST');
    this.operation = 'ADJUST';
    this.reason = 'RESTOCK';
    this.quantity = 0;
    this.note = '';
    this.errorMsg.set('');
    this.successMsg.set('');
  }

  closeEditor(): void {
    this.editorItem.set(null);
    this.editorMode.set(null);
  }

  projectedOnHand(): number {
    const onHand = this.editorItem()?.inventory?.onHand ?? 0;
    return this.operation === 'SET' ? this.quantity : onHand + this.quantity;
  }

  canSave(): boolean {
    if (!Number.isInteger(this.quantity)) {
      return false;
    }
    if (this.editorMode() === 'INITIALIZE' || this.operation === 'SET') {
      return this.quantity >= 0;
    }
    return this.projectedOnHand() >= 0;
  }

  saveInventory(): void {
    const context = this.context();
    const item = this.editorItem();
    const mode = this.editorMode();
    if (!context || !item || !mode || !this.canSave()) {
      return;
    }
    this.saving.set(true);
    this.errorMsg.set('');
    this.successMsg.set('');
    const request = mode === 'INITIALIZE'
      ? this.inventoryService.initialize(context.businessId, item.listingId, {
        onHand: this.quantity,
        note: this.note.trim() || null,
      })
      : this.inventoryService.adjust(context.businessId, item.listingId, item.inventory!.version, {
        operation: this.operation,
        quantity: this.quantity,
        reason: this.reason,
        note: this.note.trim() || null,
      });

    request.subscribe({
      next: inventory => {
        this.items.update(items => items.map(current =>
          current.listingId === item.listingId
            ? { ...current, inventoryState: 'INITIALIZED', inventory }
            : current,
        ));
        this.saving.set(false);
        this.closeEditor();
        this.successMsg.set(mode === 'INITIALIZE' ? 'Inventory initialized.' : 'Inventory adjustment saved.');
      },
      error: error => {
        this.saving.set(false);
        if (error instanceof HttpErrorResponse && error.status === 409) {
          this.errorMsg.set('Inventory changed in another session. The latest balance has been reloaded.');
          this.closeEditor();
          this.loadItems();
          return;
        }
        this.errorMsg.set(this.errorMessage(error, 'Inventory could not be saved.'));
      },
    });
  }

  openHistory(item: InventoryCatalogItem): void {
    const context = this.context();
    if (!context) {
      return;
    }
    this.historyItem.set(item);
    this.movements.set([]);
    this.loadingHistory.set(true);
    this.errorMsg.set('');
    this.inventoryService.movements(context.businessId, item.listingId).subscribe({
      next: page => {
        this.movements.set(page.data);
        this.loadingHistory.set(false);
      },
      error: error => {
        this.errorMsg.set(this.errorMessage(error, 'Movement history could not be loaded.'));
        this.loadingHistory.set(false);
      },
    });
  }

  closeHistory(): void {
    this.historyItem.set(null);
    this.movements.set([]);
  }

  reasonLabel(reason: InventoryReason): string {
    return reason.toLowerCase().replace(/_/g, ' ').replace(/\b\w/g, letter => letter.toUpperCase());
  }

  private errorMessage(error: unknown, fallback: string): string {
    if (error instanceof HttpErrorResponse) {
      return error.error?.error?.message || fallback;
    }
    return fallback;
  }
}
