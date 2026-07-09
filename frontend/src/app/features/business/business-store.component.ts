import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { BusinessStore } from '../../core/models/business-store.model';
import { BusinessStoreService } from '../../core/services/business-store.service';
import { ToastService } from '../../core/services/toast.service';
import { StatusPillComponent } from '../../shared/components/ui/status-pill.component';

@Component({
  selector: 'app-business-store',
  standalone: true,
  imports: [FormsModule, StatusPillComponent],
  template: `
    <section class="store-page">
      <header class="store-header">
        <div>
          <p class="eyebrow">Store profile</p>
          <h1>{{ store()?.name || 'Business Store' }}</h1>
        </div>
        @if (store()) {
          <app-ui-status-pill>{{ store()?.status }}</app-ui-status-pill>
        }
      </header>

      @if (loading()) {
        <div class="state-panel">Loading store profile</div>
      } @else if (errorMsg() && !store()) {
        <div class="state-panel error-message">{{ errorMsg() }}</div>
      } @else if (store()) {
        <form class="store-form" (ngSubmit)="save()">
          <div class="form-grid">
            <label class="field">
              <span>Store name</span>
              <input name="name" [(ngModel)]="name" maxlength="160" [disabled]="saving()" />
            </label>

            <label class="field">
              <span>Public slug</span>
              <input name="slug" [(ngModel)]="slug" maxlength="100" [disabled]="saving()" />
            </label>

            <label class="field">
              <span>Support email</span>
              <input name="supportEmail" [(ngModel)]="supportEmail" maxlength="320" [disabled]="saving()" />
            </label>

            <label class="field">
              <span>Support phone</span>
              <input name="supportPhone" [(ngModel)]="supportPhone" maxlength="32" [disabled]="saving()" />
            </label>

            <label class="field">
              <span>Logo URL</span>
              <input name="logoUrl" [(ngModel)]="logoUrl" maxlength="2048" [disabled]="saving()" />
            </label>

            <label class="field">
              <span>Banner URL</span>
              <input name="bannerUrl" [(ngModel)]="bannerUrl" maxlength="2048" [disabled]="saving()" />
            </label>
          </div>

          <label class="field full-width">
            <span>Description</span>
            <textarea name="description" [(ngModel)]="description" maxlength="1000" [disabled]="saving()"></textarea>
          </label>

          <div class="store-summary">
            <span>Public path</span>
            <strong>/stores/{{ slug || store()?.slug }}</strong>
          </div>

          @if (errorMsg()) {
            <div class="error-message">{{ errorMsg() }}</div>
          }

          <div class="actions">
            <button type="submit" class="primary-btn" [disabled]="saving()">
              {{ saving() ? 'Saving' : 'Save store' }}
            </button>
          </div>
        </form>
      }
    </section>
  `,
  styles: [`
    .store-page {
      display: flex;
      flex-direction: column;
      gap: 1.25rem;
      max-width: 980px;
    }

    .store-header {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 1rem;
    }

    .eyebrow {
      color: var(--color-text-muted);
      font-size: 0.75rem;
      font-weight: 800;
      letter-spacing: 0.08em;
      margin-bottom: 0.25rem;
      text-transform: uppercase;
    }

    .store-header h1 {
      font-size: 1.75rem;
      letter-spacing: 0;
    }

    .store-form,
    .state-panel {
      background: var(--color-bg-secondary);
      border: 1px solid var(--color-border);
      border-radius: var(--radius-lg);
      padding: 1.25rem;
    }

    .store-form {
      display: flex;
      flex-direction: column;
      gap: 1rem;
    }

    .form-grid {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 1rem;
    }

    .field {
      display: flex;
      flex-direction: column;
      gap: 0.375rem;
    }

    .field span,
    .store-summary span {
      color: var(--color-text-secondary);
      font-size: 0.8125rem;
      font-weight: 650;
    }

    .field input,
    .field textarea {
      width: 100%;
      min-height: 40px;
      padding: 0.625rem 0.75rem;
      background: var(--color-bg-tertiary);
      border: 1px solid var(--color-border);
      border-radius: var(--radius-md);
      color: var(--color-text-primary);
      font-size: 0.875rem;
      outline: none;
    }

    .field textarea {
      min-height: 116px;
      resize: vertical;
    }

    .field input:focus,
    .field textarea:focus {
      border-color: var(--color-accent);
      box-shadow: 0 0 0 3px var(--color-accent-muted);
    }

    .store-summary {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 1rem;
      min-height: 44px;
      padding: 0.75rem;
      background: var(--color-bg-tertiary);
      border: 1px solid var(--color-border);
      border-radius: var(--radius-md);
    }

    .store-summary strong {
      color: var(--color-accent);
      font-weight: 750;
      overflow-wrap: anywhere;
    }

    .error-message {
      color: var(--color-danger);
      background: rgba(244, 63, 94, 0.08);
      border: 1px solid rgba(244, 63, 94, 0.2);
      border-radius: var(--radius-md);
      padding: 0.625rem 0.75rem;
      font-size: 0.875rem;
    }

    .actions {
      display: flex;
      justify-content: flex-end;
    }

    .primary-btn {
      min-height: 40px;
      padding: 0 0.875rem;
      border-radius: var(--radius-md);
      font-weight: 750;
      cursor: pointer;
      border: 1px solid transparent;
      background: var(--color-accent);
      color: #0c0c0e;
    }

    .primary-btn:disabled {
      opacity: 0.55;
      cursor: not-allowed;
    }

    @media (max-width: 760px) {
      .store-header,
      .store-summary {
        align-items: flex-start;
        flex-direction: column;
      }

      .form-grid {
        grid-template-columns: 1fr;
      }

      .actions {
        justify-content: stretch;
      }

      .primary-btn {
        width: 100%;
      }
    }
  `],
})
export class BusinessStoreComponent implements OnInit {
  private readonly businessStoreService = inject(BusinessStoreService);
  private readonly toastService = inject(ToastService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  readonly loading = signal(true);
  readonly saving = signal(false);
  readonly errorMsg = signal('');
  readonly store = signal<BusinessStore | null>(null);

  name = '';
  slug = '';
  description = '';
  logoUrl = '';
  bannerUrl = '';
  supportEmail = '';
  supportPhone = '';

  ngOnInit(): void {
    const businessId = this.route.snapshot.paramMap.get('businessId');
    if (!businessId) {
      this.errorMsg.set('Business store could not be loaded.');
      this.loading.set(false);
      return;
    }

    this.businessStoreService.getBusinessStore(businessId).subscribe({
      next: store => {
        this.store.set(store);
        this.applyStore(store);
        this.loading.set(false);
      },
      error: error => {
        this.loading.set(false);
        if (error.status === 401) {
          this.router.navigate(['/login']);
          return;
        }
        if (error.status === 403) {
          this.errorMsg.set('You do not have access to this business store.');
          return;
        }
        this.errorMsg.set('Business store could not be loaded.');
      },
    });
  }

  save(): void {
    const current = this.store();
    if (!current || !this.validate()) {
      return;
    }

    this.saving.set(true);
    this.errorMsg.set('');

    this.businessStoreService.updateBusinessStore(current.businessId, {
      name: this.name.trim(),
      slug: this.slug.trim().toLowerCase(),
      description: this.trimOrNull(this.description),
      logoUrl: this.trimOrNull(this.logoUrl),
      bannerUrl: this.trimOrNull(this.bannerUrl),
      supportEmail: this.trimOrNull(this.supportEmail),
      supportPhone: this.trimOrNull(this.supportPhone),
    }, current.version).subscribe({
      next: store => {
        this.store.set(store);
        this.applyStore(store);
        this.saving.set(false);
        this.toastService.success('Business store saved.');
      },
      error: error => {
        this.saving.set(false);
        if (error.status === 401) {
          this.router.navigate(['/login']);
          return;
        }
        if (error.status === 403) {
          this.errorMsg.set('You do not have permission to edit this store.');
          return;
        }
        if (error.status === 409 && error.error?.error?.code === 'BUSINESS_STORE_SLUG_CONFLICT') {
          this.errorMsg.set('That store slug is already in use.');
          return;
        }
        if (error.status === 409) {
          this.errorMsg.set('Business store changed. Refresh and try again.');
          return;
        }
        this.errorMsg.set('Business store could not be saved.');
      },
    });
  }

  private applyStore(store: BusinessStore): void {
    this.name = store.name;
    this.slug = store.slug;
    this.description = store.description || '';
    this.logoUrl = store.logoUrl || '';
    this.bannerUrl = store.bannerUrl || '';
    this.supportEmail = store.supportEmail || '';
    this.supportPhone = store.supportPhone || '';
  }

  private validate(): boolean {
    if (!this.name.trim()) {
      this.errorMsg.set('Store name is required.');
      return false;
    }
    if (!/^[a-z0-9][a-z0-9-]{1,98}[a-z0-9]$/.test(this.slug.trim().toLowerCase())) {
      this.errorMsg.set('Store slug is invalid.');
      return false;
    }
    if (this.supportEmail.trim() && !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(this.supportEmail.trim())) {
      this.errorMsg.set('Support email is invalid.');
      return false;
    }
    if (this.supportPhone.trim() && !/^\+[1-9][0-9]{7,14}$/.test(this.supportPhone.trim())) {
      this.errorMsg.set('Support phone is invalid.');
      return false;
    }
    if (!this.validUrl(this.logoUrl) || !this.validUrl(this.bannerUrl)) {
      this.errorMsg.set('Logo and banner URLs must be http, https, or API paths.');
      return false;
    }
    return true;
  }

  private validUrl(value: string): boolean {
    const trimmed = value.trim();
    return !trimmed || /^https?:\/\/.+/i.test(trimmed) || /^\/api\/v1\/.+/.test(trimmed);
  }

  private trimOrNull(value: string): string | null {
    const trimmed = value.trim();
    return trimmed ? trimmed : null;
  }
}
