import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { IndividualSellerProfile } from '../../core/models/individual-seller.model';
import { IndividualSellerService } from '../../core/services/individual-seller.service';
import { ToastService } from '../../core/services/toast.service';

@Component({
  selector: 'app-individual-seller-activation',
  standalone: true,
  imports: [FormsModule],
  template: `
    <section class="seller-page">
      <header class="seller-header">
        <div>
          <h1>Become a Seller</h1>
          <p>Individual listings use public city and region only.</p>
        </div>
        @if (profile()) {
          <span class="status-pill">{{ profile()?.status }}</span>
        }
      </header>

      @if (profile()) {
        <section class="success-panel">
          <h2>Individual seller is active</h2>
          <dl>
            <div>
              <dt>Public location</dt>
              <dd>{{ profile()?.publicCity }}, {{ profile()?.publicRegion }}</dd>
            </div>
            <div>
              <dt>Completed sales</dt>
              <dd>{{ profile()?.completedSalesCount }}</dd>
            </div>
          </dl>
        </section>
      } @else {
        <form class="seller-form" (ngSubmit)="activate()">
          <div class="terms-panel">
            <strong>Off-platform trade disclosure</strong>
            <p>
              Individual buyers and sellers arrange payment and delivery themselves.
              MSB Commerce does not verify, hold, protect, or process off-platform payment.
            </p>
            <label class="checkbox-row">
              <input
                type="checkbox"
                name="acceptedTerms"
                [(ngModel)]="acceptedTerms"
                [disabled]="loading() || saving()"
              />
              <span>I understand and accept the individual-selling terms.</span>
            </label>
          </div>

          <div class="form-grid">
            <label class="field">
              <span>Public city</span>
              <input
                name="publicCity"
                [(ngModel)]="publicCity"
                maxlength="120"
                autocomplete="address-level2"
                [disabled]="loading() || saving()"
              />
            </label>

            <label class="field">
              <span>Public region</span>
              <input
                name="publicRegion"
                [(ngModel)]="publicRegion"
                maxlength="120"
                autocomplete="address-level1"
                [disabled]="loading() || saving()"
              />
            </label>
          </div>

          @if (errorMsg()) {
            <div class="error-message">{{ errorMsg() }}</div>
          }

          <div class="actions">
            <button type="submit" class="primary-btn" [disabled]="loading() || saving()">
              {{ loading() ? 'Checking' : saving() ? 'Activating' : 'Activate seller profile' }}
            </button>
          </div>
        </form>
      }
    </section>
  `,
  styles: [`
    .seller-page {
      display: flex;
      flex-direction: column;
      gap: 1.5rem;
      max-width: 900px;
    }

    .seller-header {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 1rem;
    }

    .seller-header h1 {
      font-size: 1.75rem;
      margin-bottom: 0.25rem;
    }

    .seller-header p {
      color: var(--color-text-muted);
      font-size: 0.875rem;
    }

    .seller-form,
    .success-panel {
      background: var(--color-bg-secondary);
      border: 1px solid var(--color-border);
      border-radius: var(--radius-lg);
      padding: 1.25rem;
    }

    .seller-form {
      display: flex;
      flex-direction: column;
      gap: 1.25rem;
    }

    .terms-panel {
      display: flex;
      flex-direction: column;
      gap: 0.625rem;
      padding: 1rem;
      background: var(--color-bg-tertiary);
      border: 1px solid var(--color-border);
      border-radius: var(--radius-md);
    }

    .terms-panel strong {
      color: var(--color-text-primary);
      font-size: 0.9375rem;
    }

    .terms-panel p,
    .checkbox-row span {
      color: var(--color-text-secondary);
      font-size: 0.875rem;
      line-height: 1.5;
    }

    .checkbox-row {
      display: flex;
      align-items: flex-start;
      gap: 0.625rem;
      cursor: pointer;
    }

    .checkbox-row input {
      margin-top: 0.25rem;
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
    .success-panel dt {
      font-size: 0.8125rem;
      font-weight: 600;
      color: var(--color-text-secondary);
    }

    .field input {
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

    .field input:focus {
      border-color: var(--color-accent);
      box-shadow: 0 0 0 3px var(--color-accent-muted);
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
      font-weight: 700;
      cursor: pointer;
      border: 1px solid transparent;
      background: var(--color-accent);
      color: #0c0c0e;
    }

    .primary-btn:disabled {
      opacity: 0.55;
      cursor: not-allowed;
    }

    .status-pill {
      display: inline-flex;
      align-items: center;
      min-height: 28px;
      padding: 0 0.625rem;
      border-radius: var(--radius-md);
      background: var(--color-accent-muted);
      color: var(--color-accent);
      font-size: 0.75rem;
      font-weight: 700;
    }

    .success-panel h2 {
      font-size: 1.125rem;
      margin-bottom: 1rem;
    }

    .success-panel dl {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 1rem;
      margin: 0;
    }

    .success-panel dd {
      margin: 0.25rem 0 0;
      color: var(--color-text-primary);
      font-weight: 700;
    }

    @media (max-width: 760px) {
      .seller-header {
        flex-direction: column;
      }

      .form-grid,
      .success-panel dl {
        grid-template-columns: 1fr;
      }

      .actions {
        justify-content: stretch;
      }

      .primary-btn {
        width: 100%;
      }
    }
  `]
})
export class IndividualSellerActivationComponent implements OnInit {
  private individualSellerService = inject(IndividualSellerService);
  private toastService = inject(ToastService);
  private router = inject(Router);

  readonly termsVersion = '2026-01';
  loading = signal(false);
  saving = signal(false);
  errorMsg = signal('');
  profile = signal<IndividualSellerProfile | null>(null);

  publicCity = '';
  publicRegion = '';
  acceptedTerms = false;

  ngOnInit(): void {
    this.loadProfile();
  }

  loadProfile(): void {
    this.loading.set(true);
    this.errorMsg.set('');

    this.individualSellerService.getMe().subscribe({
      next: response => {
        this.profile.set(response.data);
        this.loading.set(false);
      },
      error: error => {
        this.loading.set(false);
        if (error.status === 401) {
          this.router.navigate(['/login']);
          return;
        }
        if (error.status === 404) {
          this.profile.set(null);
          return;
        }
        this.errorMsg.set('Seller profile could not be loaded.');
      },
    });
  }

  activate(): void {
    if (!this.validate()) {
      return;
    }

    this.saving.set(true);
    this.errorMsg.set('');

    this.individualSellerService.activate({
      publicCity: this.publicCity.trim(),
      publicRegion: this.publicRegion.trim(),
      termsVersion: this.termsVersion,
    }).subscribe({
      next: response => {
        this.profile.set(response.data);
        this.saving.set(false);
        this.toastService.success('Individual seller profile activated.');
      },
      error: error => {
        this.saving.set(false);
        if (error.status === 401) {
          this.router.navigate(['/login']);
          return;
        }
        if (error.status === 409) {
          this.errorMsg.set('Individual seller profile is already active.');
          return;
        }
        this.errorMsg.set('Seller profile could not be activated.');
      },
    });
  }

  private validate(): boolean {
    const city = this.publicCity.trim();
    const region = this.publicRegion.trim();

    if (!this.acceptedTerms) {
      this.errorMsg.set('Accept the individual-selling terms to continue.');
      return false;
    }
    if (!city) {
      this.errorMsg.set('Public city is required.');
      return false;
    }
    if (!region) {
      this.errorMsg.set('Public region is required.');
      return false;
    }
    if (city.length > 120 || region.length > 120) {
      this.errorMsg.set('Public location is too long.');
      return false;
    }

    return true;
  }
}
