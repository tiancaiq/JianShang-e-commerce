import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AddressCreateRequest, BuyerAddress } from '../../core/models/address.model';
import { AddressBookService } from '../../core/services/address-book.service';
import { AuthService } from '../../core/services/auth.service';

interface AddressForm {
  label: string;
  recipientName: string;
  phone: string;
  line1: string;
  line2: string;
  city: string;
  region: string;
  postalCode: string;
  countryCode: string;
}

@Component({
  selector: 'app-address-book',
  standalone: true,
  imports: [FormsModule, RouterLink],
  template: `
    <section class="address-page">
      <header class="page-header">
        <div>
          <a routerLink="/account" class="back-link">Back to account</a>
          <p class="eyebrow">Marketplace account</p>
          <h1>Addresses</h1>
          <p class="subheading">{{ addresses().length }} of 20 saved</p>
        </div>
        <button
          type="button"
          class="primary-button"
          (click)="startAdd()"
          [disabled]="loading() || saving() || maximumReached()"
        >
          Add address
        </button>
      </header>

      @if (formOpen()) {
        <form class="address-form" (ngSubmit)="save()" novalidate>
          <div class="form-heading">
            <div>
              <p class="eyebrow">{{ editingAddress() ? 'Edit saved address' : 'New saved address' }}</p>
              <h2>{{ editingAddress()?.label || editingAddress()?.city || 'Address details' }}</h2>
            </div>
            <button type="button" class="quiet-button" (click)="cancelForm()" [disabled]="saving()">Cancel</button>
          </div>

          <div class="form-grid">
            <label>
              <span>Label</span>
              <input
                name="label"
                [(ngModel)]="form.label"
                maxlength="40"
                autocomplete="off"
                placeholder="Home"
                [disabled]="saving()"
              />
            </label>

            <label>
              <span>Recipient name</span>
              <input
                name="recipientName"
                [(ngModel)]="form.recipientName"
                maxlength="120"
                autocomplete="name"
                required
                [disabled]="saving()"
              />
            </label>

            <label>
              <span>Phone</span>
              <input
                name="phone"
                [(ngModel)]="form.phone"
                maxlength="32"
                autocomplete="tel"
                inputmode="tel"
                placeholder="+19495550123"
                required
                [disabled]="saving()"
              />
            </label>

            <label class="wide-field">
              <span>Address line 1</span>
              <input
                name="line1"
                [(ngModel)]="form.line1"
                maxlength="200"
                autocomplete="address-line1"
                required
                [disabled]="saving()"
              />
            </label>

            <label class="wide-field">
              <span>Address line 2</span>
              <input
                name="line2"
                [(ngModel)]="form.line2"
                maxlength="200"
                autocomplete="address-line2"
                [disabled]="saving()"
              />
            </label>

            <label>
              <span>City</span>
              <input
                name="city"
                [(ngModel)]="form.city"
                maxlength="100"
                autocomplete="address-level2"
                required
                [disabled]="saving()"
              />
            </label>

            <label>
              <span>State or region</span>
              <input
                name="region"
                [(ngModel)]="form.region"
                maxlength="100"
                autocomplete="address-level1"
                required
                [disabled]="saving()"
              />
            </label>

            <label>
              <span>Postal code</span>
              <input
                name="postalCode"
                [(ngModel)]="form.postalCode"
                maxlength="32"
                autocomplete="postal-code"
                required
                [disabled]="saving()"
              />
            </label>

            <label>
              <span>Country code</span>
              <input
                name="countryCode"
                [(ngModel)]="form.countryCode"
                maxlength="2"
                autocomplete="country"
                autocapitalize="characters"
                placeholder="US"
                required
                [disabled]="saving()"
              />
            </label>
          </div>

          @if (formError()) {
            <p class="error-message" role="alert">{{ formError() }}</p>
          }

          <div class="form-actions">
            <button type="submit" class="primary-button" [disabled]="saving()">
              {{ saving() ? 'Saving' : editingAddress() ? 'Save changes' : 'Save address' }}
            </button>
          </div>
        </form>
      }

      @if (pageError()) {
        <div class="page-message error-message" role="alert">
          <span>{{ pageError() }}</span>
          <button type="button" class="quiet-button" (click)="load()">Retry</button>
        </div>
      }

      @if (loading()) {
        <div class="loading-list" aria-label="Loading addresses">
          <span></span>
          <span></span>
        </div>
      } @else if (addresses().length === 0) {
        <section class="empty-state">
          <div class="empty-icon" aria-hidden="true">+</div>
          <h2>No saved addresses</h2>
          <button type="button" class="primary-button" (click)="startAdd()">Add address</button>
        </section>
      } @else {
        <div class="address-list">
          @for (address of addresses(); track address.id) {
            <article class="address-card" [class.default-card]="address.isDefault">
              <div class="address-copy">
                <div class="address-title">
                  <h2>{{ address.label || address.city }}</h2>
                  @if (address.isDefault) {
                    <span class="default-pill">Default</span>
                  }
                </div>
                <p class="recipient">{{ address.recipientName }} <span>{{ address.phone }}</span></p>
                <address>
                  {{ address.line1 }}@if (address.line2) {, {{ address.line2 }}}<br />
                  {{ address.city }}, {{ address.region }} {{ address.postalCode }}<br />
                  {{ address.countryCode }}
                </address>
              </div>

              @if (deleteTargetId() === address.id) {
                <div class="delete-confirmation">
                  <p>Delete {{ address.label || address.city }}?</p>
                  <div>
                    <button type="button" class="quiet-button" (click)="cancelDelete()" [disabled]="saving()">Cancel</button>
                    <button type="button" class="danger-button" (click)="confirmDelete(address)" [disabled]="saving()">
                      Delete
                    </button>
                  </div>
                </div>
              } @else {
                <div class="card-actions">
                  @if (!address.isDefault) {
                    <button type="button" class="quiet-button" (click)="setDefault(address)" [disabled]="saving()">
                      Set default
                    </button>
                  }
                  <button type="button" class="quiet-button" (click)="startEdit(address)" [disabled]="saving()">Edit</button>
                  <button type="button" class="danger-link" (click)="requestDelete(address)" [disabled]="saving()">Delete</button>
                </div>
              }
            </article>
          }
        </div>
      }
    </section>
  `,
  styles: [`
    :host {
      display: block;
      --address-surface: rgba(255, 255, 255, 0.94);
      --address-field: #fffafd;
      --address-line: var(--market-line, rgba(150, 91, 173, 0.22));
      --address-ink: var(--market-ink, #38244f);
      --address-muted: var(--market-muted, #79688a);
      --address-accent: var(--market-accent-dark, #c73588);
      color: var(--address-ink);
    }

    .address-page {
      width: min(100%, 980px);
      margin: 0 auto;
      display: grid;
      gap: 20px;
    }

    .page-header,
    .form-heading,
    .address-title,
    .card-actions,
    .form-actions,
    .page-message,
    .delete-confirmation,
    .delete-confirmation div {
      display: flex;
      align-items: center;
    }

    .page-header {
      position: relative;
      z-index: 1;
      justify-content: space-between;
      gap: 24px;
      padding: 4px 0 12px;
      border-bottom: 1px solid var(--address-line);
    }

    .page-header > div {
      min-width: 0;
    }

    .page-header > .primary-button {
      flex: 0 0 auto;
    }

    .back-link {
      display: inline-block;
      margin-bottom: 18px;
      color: var(--address-muted);
      font-weight: 750;
      text-decoration: none;
    }

    .back-link:focus-visible,
    button:focus-visible,
    input:focus-visible {
      outline: 3px solid rgba(199, 53, 136, 0.24);
      outline-offset: 2px;
    }

    .eyebrow,
    h1,
    h2,
    p {
      margin: 0;
    }

    .eyebrow {
      color: var(--address-accent);
      font-size: 0.75rem;
      font-weight: 850;
      text-transform: uppercase;
    }

    h1 {
      margin-top: 5px;
      color: var(--address-ink);
      font-size: 2rem;
      line-height: 1.08;
    }

    h2 {
      color: var(--address-ink);
    }

    .subheading {
      margin-top: 7px;
      color: var(--address-muted);
      font-size: 0.9rem;
      font-weight: 650;
    }

    button {
      min-height: 40px;
      border-radius: 8px;
      font: inherit;
      font-weight: 800;
      cursor: pointer;
    }

    button:disabled {
      cursor: not-allowed;
      opacity: 0.55;
    }

    .primary-button {
      padding: 0 18px;
      border: 0;
      color: #fff;
      background: linear-gradient(135deg, #ed5da7, #8b6fe8);
    }

    .quiet-button {
      padding: 0 13px;
      border: 1px solid var(--address-line);
      color: var(--address-ink);
      background: #fff;
    }

    .danger-button {
      padding: 0 13px;
      border: 1px solid #d84862;
      color: #fff;
      background: #c93651;
    }

    .danger-link {
      padding: 0 4px;
      border: 0;
      color: #ba304a;
      background: transparent;
    }

    .address-form {
      display: grid;
      gap: 18px;
      padding: 22px;
      border: 1px solid var(--address-line);
      border-radius: 8px;
      background: var(--address-surface);
      box-shadow: 0 14px 30px rgba(126, 77, 155, 0.08);
    }

    .form-heading {
      justify-content: space-between;
      gap: 16px;
    }

    .form-heading h2 {
      margin-top: 4px;
      font-size: 1.2rem;
    }

    .form-grid {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 14px;
    }

    label {
      display: grid;
      gap: 6px;
      min-width: 0;
    }

    label span {
      color: var(--address-muted);
      font-size: 0.8rem;
      font-weight: 750;
    }

    input {
      width: 100%;
      min-width: 0;
      min-height: 44px;
      box-sizing: border-box;
      padding: 0 12px;
      border: 1px solid var(--address-line);
      border-radius: 6px;
      color: var(--address-ink);
      background: var(--address-field);
      font: inherit;
    }

    .wide-field {
      grid-column: 1 / -1;
    }

    .form-actions {
      justify-content: flex-end;
    }

    .page-message {
      justify-content: space-between;
      gap: 12px;
    }

    .error-message {
      padding: 11px 13px;
      border: 1px solid rgba(190, 48, 76, 0.35);
      border-radius: 6px;
      color: #9f263f;
      background: #fff4f6;
      font-size: 0.88rem;
      font-weight: 700;
    }

    .loading-list,
    .address-list {
      display: grid;
      gap: 12px;
    }

    .loading-list span {
      min-height: 162px;
      border: 1px solid var(--address-line);
      border-radius: 8px;
      background: linear-gradient(100deg, #fff 20%, #fff4fa 45%, #fff 70%);
      background-size: 220% 100%;
      animation: loading 1.2s linear infinite;
    }

    @keyframes loading {
      to { background-position: -220% 0; }
    }

    @media (prefers-reduced-motion: reduce) {
      .loading-list span { animation: none; }
    }

    .address-card {
      min-height: 148px;
      display: grid;
      grid-template-columns: minmax(0, 1fr) max-content;
      align-items: stretch;
      gap: 24px;
      padding: 20px;
      border: 1px solid var(--address-line);
      border-left: 4px solid transparent;
      border-radius: 8px;
      background: var(--address-surface);
      box-shadow: 0 10px 24px rgba(126, 77, 155, 0.06);
    }

    .address-card.default-card {
      border-left-color: #2b9f82;
    }

    .address-copy {
      min-width: 0;
    }

    .address-title {
      flex-wrap: wrap;
      gap: 9px;
    }

    .address-title h2 {
      font-size: 1.08rem;
    }

    .default-pill {
      display: inline-flex;
      align-items: center;
      min-height: 24px;
      padding: 0 9px;
      border-radius: 999px;
      color: #15775f;
      background: #e4f6f0;
      font-size: 0.72rem;
      font-weight: 850;
    }

    .recipient {
      margin-top: 12px;
      font-weight: 800;
    }

    .recipient span {
      margin-left: 8px;
      color: var(--address-muted);
      font-weight: 650;
    }

    address {
      margin-top: 7px;
      color: var(--address-muted);
      font-style: normal;
      line-height: 1.5;
    }

    .card-actions {
      align-self: end;
      justify-content: flex-end;
      flex-wrap: wrap;
      gap: 8px;
    }

    .delete-confirmation {
      align-self: center;
      justify-content: flex-end;
      gap: 14px;
    }

    .delete-confirmation p {
      font-weight: 800;
    }

    .delete-confirmation div {
      gap: 8px;
    }

    .empty-state {
      min-height: 280px;
      display: grid;
      place-items: center;
      align-content: center;
      gap: 14px;
      border: 1px dashed rgba(150, 91, 173, 0.38);
      border-radius: 8px;
      background: rgba(255, 255, 255, 0.62);
      text-align: center;
    }

    .empty-icon {
      width: 52px;
      aspect-ratio: 1;
      display: grid;
      place-items: center;
      border-radius: 50%;
      color: #fff;
      background: #8b6fe8;
      font-size: 1.7rem;
      font-weight: 500;
    }

    .empty-state h2 {
      font-size: 1.1rem;
    }

    @media (max-width: 720px) {
      .page-header,
      .form-heading {
        align-items: stretch;
        flex-direction: column;
      }

      .form-grid,
      .address-card {
        grid-template-columns: 1fr;
      }

      .wide-field {
        grid-column: auto;
      }

      .card-actions,
      .delete-confirmation {
        align-self: auto;
        justify-content: flex-start;
      }

      .delete-confirmation {
        align-items: flex-start;
        flex-direction: column;
      }
    }
  `],
})
export class AddressBookComponent implements OnInit {
  private readonly addressBookService = inject(AddressBookService);
  private readonly authService = inject(AuthService);
  private readonly router = inject(Router);

  readonly addresses = signal<BuyerAddress[]>([]);
  readonly loading = signal(true);
  readonly saving = signal(false);
  readonly pageError = signal<string | null>(null);
  readonly formError = signal<string | null>(null);
  readonly formOpen = signal(false);
  readonly editingAddress = signal<BuyerAddress | null>(null);
  readonly deleteTargetId = signal<string | null>(null);

  form: AddressForm = this.emptyForm();

  ngOnInit(): void {
    this.load();
  }

  maximumReached(): boolean {
    return this.addresses().length >= 20;
  }

  load(preserveMessage = false): void {
    this.loading.set(true);
    if (!preserveMessage) {
      this.pageError.set(null);
    }
    this.addressBookService.list().subscribe({
      next: addresses => {
        this.addresses.set(addresses);
        this.loading.set(false);
      },
      error: error => {
        this.loading.set(false);
        this.handleLoadError(error);
      },
    });
  }

  startAdd(): void {
    if (this.maximumReached() || this.saving()) {
      return;
    }
    this.editingAddress.set(null);
    this.form = this.emptyForm();
    this.formError.set(null);
    this.deleteTargetId.set(null);
    this.formOpen.set(true);
  }

  startEdit(address: BuyerAddress): void {
    this.editingAddress.set(address);
    this.form = {
      label: address.label || '',
      recipientName: address.recipientName,
      phone: address.phone,
      line1: address.line1,
      line2: address.line2 || '',
      city: address.city,
      region: address.region,
      postalCode: address.postalCode,
      countryCode: address.countryCode,
    };
    this.formError.set(null);
    this.deleteTargetId.set(null);
    this.formOpen.set(true);
  }

  cancelForm(): void {
    if (this.saving()) {
      return;
    }
    this.formOpen.set(false);
    this.editingAddress.set(null);
    this.formError.set(null);
  }

  save(): void {
    const validationMessage = this.validate();
    if (validationMessage) {
      this.formError.set(validationMessage);
      return;
    }

    const request = this.normalizedRequest();
    const editing = this.editingAddress();
    const command = editing
      ? this.addressBookService.patch(editing.id, editing.version, request)
      : this.addressBookService.create(request);

    this.saving.set(true);
    this.formError.set(null);
    command.subscribe({
      next: () => {
        this.saving.set(false);
        this.formOpen.set(false);
        this.editingAddress.set(null);
        this.load();
      },
      error: error => {
        this.saving.set(false);
        this.handleCommandError(error, true);
      },
    });
  }

  setDefault(address: BuyerAddress): void {
    if (this.saving()) {
      return;
    }
    this.saving.set(true);
    this.pageError.set(null);
    this.addressBookService.setDefault(address.id, address.version).subscribe({
      next: () => {
        this.saving.set(false);
        this.load();
      },
      error: error => {
        this.saving.set(false);
        this.handleCommandError(error);
      },
    });
  }

  requestDelete(address: BuyerAddress): void {
    this.formOpen.set(false);
    this.editingAddress.set(null);
    this.deleteTargetId.set(address.id);
  }

  cancelDelete(): void {
    this.deleteTargetId.set(null);
  }

  confirmDelete(address: BuyerAddress): void {
    if (this.saving()) {
      return;
    }
    this.saving.set(true);
    this.pageError.set(null);
    this.addressBookService.delete(address.id, address.version).subscribe({
      next: () => {
        this.saving.set(false);
        this.deleteTargetId.set(null);
        this.load();
      },
      error: error => {
        this.saving.set(false);
        this.handleCommandError(error);
      },
    });
  }

  private validate(): string | null {
    const required = [
      this.form.recipientName,
      this.form.phone,
      this.form.line1,
      this.form.city,
      this.form.region,
      this.form.postalCode,
      this.form.countryCode,
    ];
    if (required.some(value => !value.trim())) {
      return 'Complete all required address fields.';
    }
    if (!/^\+[1-9][0-9]{7,14}$/.test(this.form.phone.trim())) {
      return 'Phone must use international format, such as +19495550123.';
    }
    if (!/^[A-Za-z]{2}$/.test(this.form.countryCode.trim())) {
      return 'Country code must contain two letters.';
    }
    return null;
  }

  private normalizedRequest(): AddressCreateRequest {
    return {
      label: this.optional(this.form.label),
      recipientName: this.form.recipientName.trim(),
      phone: this.form.phone.trim(),
      line1: this.form.line1.trim(),
      line2: this.optional(this.form.line2),
      city: this.form.city.trim(),
      region: this.form.region.trim(),
      postalCode: this.form.postalCode.trim(),
      countryCode: this.form.countryCode.trim().toUpperCase(),
    };
  }

  private optional(value: string): string | null {
    const trimmed = value.trim();
    return trimmed || null;
  }

  private handleCommandError(error: unknown, formCommand = false): void {
    const code = this.errorCode(error);
    if (code === 'ADDRESS_VERSION_CONFLICT') {
      const message = 'This address changed in another session. The latest version has been loaded.';
      if (formCommand) {
        this.formError.set(message);
      } else {
        this.pageError.set(message);
      }
      this.load(true);
      return;
    }
    if (code === 'ADDRESS_BOOK_LIMIT_REACHED') {
      this.formError.set('You can save up to 20 addresses.');
      return;
    }
    if (code === 'ADDRESS_INVALID') {
      this.formError.set('Check the address fields and try again.');
      return;
    }
    if (this.isUnauthorized(error)) {
      this.redirectToLogin();
      return;
    }
    const message = 'Address changes are temporarily unavailable.';
    if (formCommand) {
      this.formError.set(message);
    } else {
      this.pageError.set(message);
    }
  }

  private handleLoadError(error: unknown): void {
    if (this.isUnauthorized(error)) {
      this.redirectToLogin();
      return;
    }
    this.pageError.set('Addresses are temporarily unavailable.');
  }

  private errorCode(error: unknown): string | null {
    if (!(error instanceof HttpErrorResponse)) {
      return (error as { error?: { error?: { code?: string } } })?.error?.error?.code || null;
    }
    return error.error?.error?.code || null;
  }

  private isUnauthorized(error: unknown): boolean {
    return error instanceof HttpErrorResponse
      ? error.status === 401
      : (error as { status?: number })?.status === 401;
  }

  private redirectToLogin(): void {
    this.authService.clearUser();
    void this.router.navigate(['/login'], {
      queryParams: {
        client: 'marketplace',
        returnUrl: '/account/addresses',
      },
    });
  }

  private emptyForm(): AddressForm {
    return {
      label: '',
      recipientName: '',
      phone: '',
      line1: '',
      line2: '',
      city: '',
      region: '',
      postalCode: '',
      countryCode: '',
    };
  }
}
