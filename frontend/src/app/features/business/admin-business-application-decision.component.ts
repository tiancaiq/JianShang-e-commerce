import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { BusinessApplication, BusinessApplicationDecision } from '../../core/models/business-application.model';
import { BusinessApplicationService } from '../../core/services/business-application.service';
import { ToastService } from '../../core/services/toast.service';

@Component({
  selector: 'app-admin-business-application-decision',
  standalone: true,
  imports: [FormsModule],
  template: `
    <section class="admin-business-page">
      <header class="page-header">
        <div>
          <h1>Business Review</h1>
        </div>
      </header>

      <form class="decision-form" (ngSubmit)="submitDecision()">
        <label class="field">
          <span>Application ID</span>
          <input
            name="applicationId"
            [(ngModel)]="applicationId"
            maxlength="26"
            [disabled]="saving()"
          />
        </label>

        <label class="field">
          <span>Decision</span>
          <select name="decision" [(ngModel)]="decision" [disabled]="saving()">
            <option value="APPROVE">Approve</option>
            <option value="REJECT">Reject</option>
            <option value="REQUEST_INFORMATION">Request information</option>
          </select>
        </label>

        <label class="field field-wide">
          <span>Reason</span>
          <textarea
            name="reason"
            [(ngModel)]="reason"
            rows="5"
            maxlength="1000"
            [disabled]="saving()"
          ></textarea>
        </label>

        @if (errorMsg()) {
          <div class="error-message">{{ errorMsg() }}</div>
        }

        <button type="submit" class="primary-btn" [disabled]="saving()">
          {{ saving() ? 'Saving decision' : 'Save decision' }}
        </button>
      </form>

      @if (application()) {
        <section class="result-panel">
          <div class="result-header">
            <h2>{{ application()?.legalName }}</h2>
            <span class="status-pill">{{ application()?.status }}</span>
          </div>
          <dl>
            <div>
              <dt>Application ID</dt>
              <dd>{{ application()?.id }}</dd>
            </div>
            @if (application()?.approvedBusinessId) {
              <div>
                <dt>Business ID</dt>
                <dd>{{ application()?.approvedBusinessId }}</dd>
              </div>
            }
            @if (application()?.decisionReason) {
              <div>
                <dt>Reason</dt>
                <dd>{{ application()?.decisionReason }}</dd>
              </div>
            }
            @if (application()?.decidedAt) {
              <div>
                <dt>Decided</dt>
                <dd>{{ application()?.decidedAt }}</dd>
              </div>
            }
          </dl>
        </section>
      }
    </section>
  `,
  styles: [`
    .admin-business-page {
      display: flex;
      flex-direction: column;
      gap: 1.25rem;
      max-width: 880px;
    }

    .page-header {
      display: flex;
      justify-content: space-between;
      gap: 1rem;
    }

    .page-header h1,
    .result-header h2 {
      margin: 0;
      color: var(--color-text-primary);
      font-family: var(--font-display);
    }

    .page-header p {
      margin: 0.25rem 0 0;
      color: var(--color-text-secondary);
    }

    .decision-form,
    .result-panel {
      border: 1px solid var(--color-border);
      border-radius: var(--radius-lg);
      background: var(--color-bg-secondary);
      padding: 1.25rem;
    }

    .decision-form {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 1rem;
      align-items: end;
    }

    .field {
      display: flex;
      flex-direction: column;
      gap: 0.4rem;
    }

    .field-wide,
    .error-message {
      grid-column: 1 / -1;
    }

    .field span {
      color: var(--color-text-secondary);
      font-size: 0.8125rem;
      font-weight: 600;
    }

    input,
    select,
    textarea {
      width: 100%;
      border: 1px solid var(--color-border);
      border-radius: var(--radius-md);
      background: var(--color-bg-primary);
      color: var(--color-text-primary);
      padding: 0.75rem 0.85rem;
      font: inherit;
    }

    textarea {
      resize: vertical;
      min-height: 120px;
    }

    .primary-btn {
      justify-self: start;
      border: none;
      border-radius: var(--radius-md);
      background: var(--color-accent);
      color: #0c0c0e;
      font-weight: 700;
      padding: 0.75rem 1rem;
      cursor: pointer;
    }

    .primary-btn:disabled {
      opacity: 0.6;
      cursor: not-allowed;
    }

    .error-message {
      color: var(--color-danger);
      font-size: 0.875rem;
    }

    .result-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 1rem;
      margin-bottom: 1rem;
    }

    .status-pill {
      border-radius: var(--radius-md);
      background: var(--color-accent-muted);
      color: var(--color-accent);
      font-size: 0.75rem;
      font-weight: 700;
      padding: 0.35rem 0.55rem;
      white-space: nowrap;
    }

    dl {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 0.85rem;
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

    @media (max-width: 720px) {
      .decision-form,
      dl {
        grid-template-columns: 1fr;
      }
    }
  `],
})
export class AdminBusinessApplicationDecisionComponent {
  private readonly businessApplicationService = inject(BusinessApplicationService);
  private readonly toastService = inject(ToastService);
  private readonly router = inject(Router);

  applicationId = '';
  decision: BusinessApplicationDecision = 'APPROVE';
  reason = '';

  saving = signal(false);
  errorMsg = signal('');
  application = signal<BusinessApplication | null>(null);

  submitDecision(): void {
    const id = this.applicationId.trim();
    const reason = this.reason.trim().replace(/\s+/g, ' ');

    if (!id) {
      this.errorMsg.set('Application ID is required.');
      return;
    }
    if (!reason) {
      this.errorMsg.set('Decision reason is required.');
      return;
    }

    this.saving.set(true);
    this.errorMsg.set('');

    this.businessApplicationService.decide(id, {
      decision: this.decision,
      reason,
    }).subscribe({
      next: application => {
        this.application.set(application);
        this.saving.set(false);
        this.toastService.success('Business application decision saved.');
      },
      error: error => {
        this.saving.set(false);
        if (error.status === 401) {
          this.router.navigate(['/login']);
          return;
        }
        if (error.status === 403) {
          this.errorMsg.set('You need platform admin access for this action.');
          return;
        }
        if (error.status === 404) {
          this.errorMsg.set('Business application was not found.');
          return;
        }
        if (error.status === 409) {
          this.errorMsg.set('Only pending or under-review applications can receive a decision.');
          return;
        }
        this.errorMsg.set('Business application decision could not be saved.');
      },
    });
  }
}
