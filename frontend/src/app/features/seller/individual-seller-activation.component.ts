import { Component, EventEmitter, OnInit, Output, inject, signal } from '@angular/core';
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
          <p class="eyebrow">Marketplace account</p>
          <h1>Become a Seller</h1>
          <p>Register an individual seller profile before creating your first listing.</p>
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
          <div class="form-intro">
            <span class="intro-mark">M</span>
            <div>
              <h2>Start with your public seller details.</h2>
              <p>Only your public location is shown on marketplace listings.</p>
            </div>
          </div>

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
              <span>City</span>
              <input
                name="publicCity"
                [(ngModel)]="publicCity"
                maxlength="120"
                autocomplete="address-level2"
                [disabled]="loading() || saving()"
              />
            </label>

            <label class="field">
              <span>County / region</span>
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
    :host {
      display: block;
      --seller-surface: var(--color-bg-secondary);
      --seller-panel: var(--color-bg-tertiary);
      --seller-border: var(--color-border);
      --seller-text: var(--color-text-primary);
      --seller-muted: var(--color-text-muted);
      --seller-soft: var(--color-bg-tertiary);
      --seller-accent: var(--color-accent);
      --seller-accent-strong: var(--color-accent);
      --seller-button-bg: var(--color-accent);
      --seller-button-text: #0c0c0e;
      --seller-shadow: none;
    }

    :host-context(.marketplace-shell) {
      --seller-surface: rgba(255, 255, 255, 0.94);
      --seller-panel: #fff8fc;
      --seller-border: var(--market-line);
      --seller-text: var(--market-ink);
      --seller-muted: var(--market-muted);
      --seller-soft: #fff0f7;
      --seller-accent: var(--market-accent-dark);
      --seller-accent-strong: #8b6fe8;
      --seller-button-bg: linear-gradient(135deg, #ff85bd, #8b6fe8);
      --seller-button-text: #fff;
      --seller-shadow: 0 18px 44px rgba(143, 92, 144, 0.12);
    }

    .seller-page {
      display: flex;
      flex-direction: column;
      gap: 1.5rem;
      max-width: 920px;
      margin: 0 auto;
    }

    .seller-header {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 1rem;
      padding: 0.5rem 0.15rem 0;
    }

    .eyebrow {
      margin: 0 0 0.35rem;
      color: var(--seller-accent);
      font-size: 0.76rem;
      font-weight: 900;
      letter-spacing: 0.08em;
      text-transform: uppercase;
    }

    .seller-header h1 {
      color: var(--seller-text);
      font-family: var(--font-display);
      font-size: clamp(2rem, 4vw, 3rem);
      line-height: 1;
      margin-bottom: 0.25rem;
      letter-spacing: 0;
    }

    .seller-header p {
      color: var(--seller-muted);
      font-size: 1rem;
      line-height: 1.55;
      margin: 0;
    }

    .seller-form,
    .success-panel {
      background: var(--seller-surface);
      border: 1px solid var(--seller-border);
      border-radius: 8px;
      padding: clamp(1rem, 3vw, 1.45rem);
      box-shadow: var(--seller-shadow);
    }

    .seller-form {
      display: flex;
      flex-direction: column;
      gap: 1.25rem;
    }

    .form-intro {
      display: flex;
      gap: 0.9rem;
      align-items: center;
      padding: 1rem;
      border: 1px solid var(--seller-border);
      border-radius: 8px;
      background:
        linear-gradient(135deg, rgba(255, 240, 247, 0.9), rgba(245, 239, 255, 0.95));
    }

    .intro-mark {
      width: 2.7rem;
      height: 2.7rem;
      display: grid;
      place-items: center;
      flex: 0 0 auto;
      border-radius: 8px;
      background: linear-gradient(135deg, #ff85bd, #8b6fe8);
      color: #fff;
      font-weight: 900;
      box-shadow: 0 12px 24px rgba(190, 58, 131, 0.2);
    }

    .form-intro h2 {
      margin: 0;
      color: var(--seller-text);
      font-family: var(--font-display);
      font-size: 1.25rem;
      letter-spacing: 0;
    }

    .form-intro p {
      margin: 0.25rem 0 0;
      color: var(--seller-muted);
      font-size: 0.9rem;
      line-height: 1.45;
    }

    .terms-panel {
      display: flex;
      flex-direction: column;
      gap: 0.625rem;
      padding: 1rem;
      background: var(--seller-panel);
      border: 1px solid var(--seller-border);
      border-radius: 8px;
    }

    .terms-panel strong {
      color: var(--seller-text);
      font-size: 0.9375rem;
    }

    .terms-panel p,
    .checkbox-row span {
      color: var(--seller-muted);
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
      font-weight: 800;
      color: var(--seller-muted);
    }

    .field input {
      width: 100%;
      min-height: 44px;
      padding: 0.625rem 0.75rem;
      background: var(--seller-panel);
      border: 1px solid var(--seller-border);
      border-radius: 8px;
      color: var(--seller-text);
      font-size: 0.95rem;
      outline: none;
    }

    .field input:focus {
      border-color: var(--seller-accent);
      box-shadow: 0 0 0 3px rgba(244, 114, 182, 0.18);
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
      min-height: 44px;
      padding: 0 1rem;
      border-radius: 8px;
      font-weight: 900;
      cursor: pointer;
      border: 1px solid transparent;
      background: var(--seller-button-bg);
      color: var(--seller-button-text);
      box-shadow: 0 14px 26px rgba(190, 58, 131, 0.18);
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
      border-radius: 999px;
      background: var(--seller-soft);
      color: var(--seller-accent);
      font-size: 0.75rem;
      font-weight: 900;
    }

    .success-panel h2 {
      color: var(--seller-text);
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
      color: var(--seller-text);
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

  @Output() sellerActivated = new EventEmitter<IndividualSellerProfile>();

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
      next: profile => {
        this.profile.set(profile);
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
      next: profile => {
        this.profile.set(profile);
        this.saving.set(false);
        this.sellerActivated.emit(profile);
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
