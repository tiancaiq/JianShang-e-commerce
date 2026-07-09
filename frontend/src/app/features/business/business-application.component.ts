import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { BusinessApplication } from '../../core/models/business-application.model';
import { BusinessApplicationService } from '../../core/services/business-application.service';
import { ToastService } from '../../core/services/toast.service';
import { StatusPillComponent } from '../../shared/components/ui/status-pill.component';

@Component({
  selector: 'app-business-application',
  standalone: true,
  imports: [FormsModule, RouterLink, StatusPillComponent],
  template: `
    <section class="business-page">
      <header class="business-header">
        <div>
          <h1>Business Application</h1>
          <p>Create a draft application for business seller onboarding.</p>
        </div>
        @if (application()) {
          <app-ui-status-pill>{{ application()?.status }}</app-ui-status-pill>
        }
      </header>

      @if (loading()) {
        <section class="success-panel">
          <h2>Loading business account</h2>
          <p class="status-copy">Checking your current business application status.</p>
        </section>
      } @else if (application()) {
        <section class="success-panel">
          <h2>{{ applicationTitle() }}</h2>
          <p class="status-copy">{{ applicationDescription() }}</p>
          <dl>
            <div>
              <dt>Legal name</dt>
              <dd>{{ application()?.legalName }}</dd>
            </div>
            <div>
              <dt>Public location</dt>
              <dd>{{ application()?.publicCity }}, {{ application()?.publicRegion }}</dd>
            </div>
            @if (application()?.submittedAt) {
              <div>
                <dt>Submitted</dt>
                <dd>{{ application()?.submittedAt }}</dd>
              </div>
            }
            @if (application()?.decidedAt) {
              <div>
                <dt>Reviewed</dt>
                <dd>{{ application()?.decidedAt }}</dd>
              </div>
            }
            @if (application()?.decisionReason) {
              <div>
                <dt>Decision reason</dt>
                <dd>{{ application()?.decisionReason }}</dd>
              </div>
            }
            @if (application()?.approvedBusinessId) {
              <div>
                <dt>Business ID</dt>
                <dd>{{ application()?.approvedBusinessId }}</dd>
              </div>
            }
          </dl>
          @if (application()?.approvedBusinessId) {
            <div class="approved-actions">
              <a class="secondary-link" [routerLink]="['/seller/businesses', application()?.approvedBusinessId, 'store']">
                Store profile
              </a>
            </div>
          }
          @if (application()?.status === 'REJECTED') {
            <div class="approved-actions">
              <button type="button" class="secondary-button" (click)="startNewApplication()">
                Start new application
              </button>
            </div>
          }
          @if (errorMsg()) {
            <div class="error-message">{{ errorMsg() }}</div>
          }
          @if (application()?.status === 'DRAFT') {
            <div class="actions">
              <button type="button" class="primary-btn" [disabled]="saving() || submitting()" (click)="submitApplication()">
                {{ submitting() ? 'Submitting' : 'Submit application' }}
              </button>
            </div>
          }
        </section>
      } @else {
        <form class="business-form" (ngSubmit)="createDraft()">
          <div class="form-grid">
            <label class="field">
              <span>Legal business name</span>
              <input name="legalName" [(ngModel)]="legalName" maxlength="200" [disabled]="saving()" />
            </label>

            <label class="field">
              <span>Business type</span>
              <select name="businessType" [(ngModel)]="businessType" [disabled]="saving()">
                <option value="LLC">LLC</option>
                <option value="CORPORATION">Corporation</option>
                <option value="SOLE_PROPRIETOR">Sole proprietor</option>
                <option value="PARTNERSHIP">Partnership</option>
              </select>
            </label>

            <label class="field">
              <span>Country</span>
              <input name="country" [(ngModel)]="country" maxlength="2" [disabled]="saving()" />
            </label>

            <label class="field">
              <span>Contact email</span>
              <input name="contactEmail" [(ngModel)]="contactEmail" maxlength="320" [disabled]="saving()" />
            </label>

            <label class="field">
              <span>Contact phone</span>
              <div class="phone-input">
                <select name="contactPhoneCountry" [(ngModel)]="contactPhoneCountryCode" [disabled]="saving()">
                  @for (country of phoneCountries; track country.code) {
                    <option [value]="country.code">{{ country.label }}</option>
                  }
                </select>
                <input
                  name="contactPhoneNumber"
                  [(ngModel)]="contactPhoneNumber"
                  inputmode="tel"
                  placeholder="9495551234"
                  [disabled]="saving()"
                />
              </div>
            </label>

            <label class="field">
              <span>Website URL</span>
              <input name="websiteUrl" [(ngModel)]="websiteUrl" maxlength="2048" [disabled]="saving()" />
            </label>

            <label class="field">
              <span>Public city</span>
              <input name="publicCity" [(ngModel)]="publicCity" maxlength="120" [disabled]="saving()" />
            </label>

            <label class="field">
              <span>Public region</span>
              <input name="publicRegion" [(ngModel)]="publicRegion" maxlength="120" [disabled]="saving()" />
            </label>
          </div>

          <label class="field full-width">
            <span>Description</span>
            <textarea name="description" [(ngModel)]="description" maxlength="1000" [disabled]="saving()"></textarea>
          </label>

          @if (errorMsg()) {
            <div class="error-message">{{ errorMsg() }}</div>
          }

          <div class="actions">
            <button type="submit" class="primary-btn" [disabled]="saving()">
              {{ saving() ? 'Saving' : 'Save draft' }}
            </button>
          </div>
        </form>
      }
    </section>
  `,
  styles: [`
    .business-page {
      display: flex;
      flex-direction: column;
      gap: 1.5rem;
      max-width: 960px;
    }

    .business-header {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 1rem;
    }

    .business-header h1 {
      font-size: 1.75rem;
      margin-bottom: 0.25rem;
    }

    .business-header p {
      color: var(--color-text-muted);
      font-size: 0.875rem;
    }

    .business-form,
    .success-panel {
      background: var(--color-bg-secondary);
      border: 1px solid var(--color-border);
      border-radius: var(--radius-lg);
      padding: 1.25rem;
    }

    .business-form {
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
    .success-panel dt {
      font-size: 0.8125rem;
      font-weight: 600;
      color: var(--color-text-secondary);
    }

    .field input,
    .field select,
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

    .phone-input {
      display: grid;
      grid-template-columns: minmax(112px, 0.42fr) minmax(0, 1fr);
      gap: 0.5rem;
    }

    .phone-input select,
    .phone-input input {
      min-width: 0;
    }

    .field textarea {
      min-height: 112px;
      resize: vertical;
    }

    .field input:focus,
    .field select:focus,
    .field textarea:focus {
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

    .approved-actions {
      display: flex;
      justify-content: flex-end;
      margin-top: 1rem;
    }

    .secondary-link {
      min-height: 40px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      padding: 0 0.875rem;
      border: 1px solid var(--color-border);
      border-radius: var(--radius-md);
      color: var(--color-accent);
      font-weight: 700;
      text-decoration: none;
    }

    .secondary-button {
      min-height: 40px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      padding: 0 0.875rem;
      border: 1px solid var(--color-border);
      border-radius: var(--radius-md);
      background: transparent;
      color: var(--color-accent);
      font: inherit;
      font-weight: 700;
      cursor: pointer;
    }

    .secondary-link:hover,
    .secondary-button:hover {
      background: var(--color-accent-muted);
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

    .business-header app-ui-status-pill {
      --ui-pill-radius: var(--radius-md);
    }

    .success-panel h2 {
      font-size: 1.125rem;
      margin-bottom: 1rem;
    }

    .status-copy {
      margin: -0.5rem 0 1rem;
      color: var(--color-text-muted);
      font-size: 0.875rem;
      font-weight: 700;
      line-height: 1.5;
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
      .business-header {
        flex-direction: column;
      }

      .form-grid,
      .success-panel dl {
        grid-template-columns: 1fr;
      }

      .actions {
        justify-content: stretch;
      }

      .primary-btn,
      .secondary-link,
      .secondary-button {
        width: 100%;
        justify-content: center;
      }

      .phone-input {
        grid-template-columns: 1fr;
      }
    }
  `]
})
export class BusinessApplicationComponent implements OnInit {
  private businessApplicationService = inject(BusinessApplicationService);
  private toastService = inject(ToastService);
  private router = inject(Router);

  loading = signal(false);
  saving = signal(false);
  submitting = signal(false);
  errorMsg = signal('');
  application = signal<BusinessApplication | null>(null);

  legalName = '';
  businessType = 'LLC';
  country = 'US';
  contactEmail = '';
  contactPhoneCountryCode = '+1';
  contactPhoneNumber = '';
  publicCity = '';
  publicRegion = '';
  websiteUrl = '';
  description = '';

  phoneCountries = [
    { code: '+1', label: 'US/CA +1' },
    { code: '+44', label: 'UK +44' },
    { code: '+61', label: 'AU +61' },
    { code: '+81', label: 'JP +81' },
    { code: '+86', label: 'CN +86' },
    { code: '+91', label: 'IN +91' },
  ];

  ngOnInit(): void {
    this.loadCurrentApplication();
  }

  loadCurrentApplication(): void {
    this.loading.set(true);
    this.errorMsg.set('');

    this.businessApplicationService.getCurrentApplication().subscribe({
      next: application => {
        this.application.set(application);
        this.loading.set(false);
      },
      error: error => {
        this.loading.set(false);
        if (error.status === 401) {
          this.router.navigate(['/login']);
          return;
        }
        this.errorMsg.set('Business account status could not be loaded.');
      },
    });
  }

  createDraft(): void {
    if (!this.validate()) {
      return;
    }

    this.saving.set(true);
    this.errorMsg.set('');

    this.businessApplicationService.createDraft({
      legalName: this.legalName.trim(),
      businessType: this.businessType.trim().toUpperCase(),
      country: this.country.trim().toUpperCase(),
      contactEmail: this.contactEmail.trim(),
      contactPhone: this.normalizedContactPhone(),
      publicCity: this.publicCity.trim(),
      publicRegion: this.publicRegion.trim(),
      websiteUrl: this.trimOrNull(this.websiteUrl),
      description: this.trimOrNull(this.description),
    }).subscribe({
      next: application => {
        this.application.set(application);
        this.saving.set(false);
        this.toastService.success('Business application draft saved.');
      },
      error: error => {
        this.saving.set(false);
        if (error.status === 401) {
          this.router.navigate(['/login']);
          return;
        }
        if (error.status === 409) {
          this.errorMsg.set('A business application or approved business account already exists.');
          return;
        }
        this.errorMsg.set('Business application could not be saved.');
      },
    });
  }

  submitApplication(): void {
    const current = this.application();
    if (!current) {
      return;
    }

    this.submitting.set(true);
    this.errorMsg.set('');

    this.businessApplicationService.submit(current.id, current.version).subscribe({
      next: application => {
        this.application.set(application);
        this.submitting.set(false);
        this.toastService.success('Business application submitted.');
      },
      error: error => {
        this.submitting.set(false);
        if (error.status === 401) {
          this.router.navigate(['/login']);
          return;
        }
        if (error.status === 409) {
          this.errorMsg.set('Business application changed. Refresh and try again.');
          return;
        }
        this.errorMsg.set('Business application could not be submitted.');
      },
    });
  }

  startNewApplication(): void {
    if (this.application()?.status !== 'REJECTED') {
      return;
    }
    this.application.set(null);
    this.errorMsg.set('');
    this.clearForm();
  }

  applicationTitle(): string {
    switch (this.application()?.status) {
      case 'DRAFT':
        return 'Draft saved';
      case 'PENDING_VERIFICATION':
        return 'Application pending';
      case 'UNDER_REVIEW':
        return 'Application under review';
      case 'APPROVED':
        return 'Business account approved';
      case 'REJECTED':
        return 'Application rejected';
      case 'INFORMATION_REQUESTED':
        return 'More information requested';
      case 'VERIFICATION_FAILED':
        return 'Verification failed';
      default:
        return 'Business application';
    }
  }

  applicationDescription(): string {
    switch (this.application()?.status) {
      case 'DRAFT':
        return 'Finish and submit this draft when the business details are ready for review.';
      case 'PENDING_VERIFICATION':
      case 'UNDER_REVIEW':
        return 'Your application is waiting for platform review. New applications are disabled while review is active.';
      case 'APPROVED':
        return 'This user already has an approved business account. Manage the store profile from this portal.';
      case 'REJECTED':
        return 'Review the decision reason, then start a new application with corrected information.';
      case 'INFORMATION_REQUESTED':
        return 'The reviewer needs more information before this business can be approved.';
      case 'VERIFICATION_FAILED':
        return 'Verification could not be completed for this application.';
      default:
        return 'Business onboarding status is available here.';
    }
  }

  private validate(): boolean {
    if (!this.legalName.trim()) {
      this.errorMsg.set('Legal business name is required.');
      return false;
    }
    if (!/^[A-Z_]+$/.test(this.businessType.trim().toUpperCase())) {
      this.errorMsg.set('Business type is invalid.');
      return false;
    }
    if (!/^[A-Z]{2}$/.test(this.country.trim().toUpperCase())) {
      this.errorMsg.set('Country must be two letters.');
      return false;
    }
    if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(this.contactEmail.trim())) {
      this.errorMsg.set('Contact email is invalid.');
      return false;
    }
    const phone = this.normalizedContactPhone();
    if (phone && !/^\+[1-9][0-9]{7,14}$/.test(phone)) {
      this.errorMsg.set('Contact phone number is invalid.');
      return false;
    }
    if (!this.publicCity.trim()) {
      this.errorMsg.set('Public city is required.');
      return false;
    }
    if (!this.publicRegion.trim()) {
      this.errorMsg.set('Public region is required.');
      return false;
    }
    const website = this.websiteUrl.trim();
    if (website && !/^https?:\/\/.+/i.test(website)) {
      this.errorMsg.set('Website URL must start with http:// or https://.');
      return false;
    }
    return true;
  }

  private trimOrNull(value: string): string | null {
    const trimmed = value.trim();
    return trimmed ? trimmed : null;
  }

  private clearForm(): void {
    this.legalName = '';
    this.businessType = 'LLC';
    this.country = 'US';
    this.contactEmail = '';
    this.contactPhoneCountryCode = '+1';
    this.contactPhoneNumber = '';
    this.publicCity = '';
    this.publicRegion = '';
    this.websiteUrl = '';
    this.description = '';
  }

  private normalizedContactPhone(): string | null {
    const digits = this.contactPhoneNumber.replace(/\D/g, '');
    if (!digits) {
      return null;
    }
    const countryCode = this.contactPhoneCountryCode.trim();
    return `${countryCode}${digits}`;
  }
}
