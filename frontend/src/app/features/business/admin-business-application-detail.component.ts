import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import {
  BusinessApplication,
  BusinessApplicationDecision,
} from '../../core/models/business-application.model';
import { BusinessApplicationService } from '../../core/services/business-application.service';
import { ToastService } from '../../core/services/toast.service';

@Component({
  selector: 'app-admin-business-application-detail',
  standalone: true,
  imports: [FormsModule, RouterLink],
  template: `
    <section class="admin-business-detail">
      <header class="page-header">
        <div>
          <a routerLink="/admin/business-applications">Business Applications</a>
          <h1>{{ application()?.legalName || 'Business application' }}</h1>
        </div>

        @if (application(); as data) {
          <div class="header-meta">
            <span class="status-pill">{{ data.status }}</span>
            <span>Version {{ data.version }}</span>
          </div>
        }
      </header>

      @if (loading()) {
        <div class="state-panel state-panel-loading" aria-live="polite">
          <span class="loading-dot"></span>
          <div>
            <strong>Loading business application</strong>
            <p>Fetching the latest review record.</p>
          </div>
        </div>
      } @else if (errorMsg()) {
        <div class="state-panel error">
          <div>
            <strong>{{ errorMsg() }}</strong>
            <p>The record may have moved, or your admin session may need attention.</p>
          </div>
          <button type="button" class="secondary-btn" data-testid="detail-retry" (click)="loadApplication()" [disabled]="loading()">
            Retry
          </button>
        </div>
      } @else if (application(); as data) {
        <div class="detail-grid">
          <section class="detail-section">
            <h2>Business</h2>
            <dl>
              <div>
                <dt>Application ID</dt>
                <dd>{{ data.id }}</dd>
              </div>
              <div>
                <dt>Legal name</dt>
                <dd>{{ data.legalName }}</dd>
              </div>
              <div>
                <dt>Business type</dt>
                <dd>{{ data.businessType }}</dd>
              </div>
              <div>
                <dt>Country</dt>
                <dd>{{ data.country }}</dd>
              </div>
              <div>
                <dt>Public location</dt>
                <dd>{{ data.publicCity }}, {{ data.publicRegion }}</dd>
              </div>
              <div>
                <dt>Website</dt>
                <dd>{{ data.websiteUrl || 'None' }}</dd>
              </div>
            </dl>
          </section>

          <section class="detail-section">
            <h2>Applicant</h2>
            <dl>
              <div>
                <dt>Applicant user ID</dt>
                <dd>{{ data.applicantUserId }}</dd>
              </div>
              <div>
                <dt>Email</dt>
                <dd>{{ data.contactEmail }}</dd>
              </div>
              <div>
                <dt>Phone</dt>
                <dd>{{ data.contactPhone || 'None' }}</dd>
              </div>
            </dl>
          </section>

          <section class="detail-section">
            <h2>Review</h2>
            <dl>
              <div>
                <dt>Status</dt>
                <dd>{{ data.status }}</dd>
              </div>
              <div>
                <dt>Submitted</dt>
                <dd>{{ data.submittedAt || 'Not submitted' }}</dd>
              </div>
              <div>
                <dt>Reviewer</dt>
                <dd>{{ data.reviewerUserId || 'None' }}</dd>
              </div>
              <div>
                <dt>Approved business</dt>
                <dd>{{ data.approvedBusinessId || 'None' }}</dd>
              </div>
              <div>
                <dt>Decision reason</dt>
                <dd>{{ data.decisionReason || 'None' }}</dd>
              </div>
              <div>
                <dt>Decided</dt>
                <dd>{{ data.decidedAt || 'None' }}</dd>
              </div>
            </dl>
          </section>

          @if (canDecide(data)) {
            <section class="detail-section detail-section-wide">
              <h2>Decision</h2>
              <form class="decision-form" (ngSubmit)="submitDecision()">
                <label>
                  <span>Decision</span>
                  <select name="decision" [(ngModel)]="decision" [disabled]="savingDecision()">
                    <option value="APPROVE">Approve</option>
                    <option value="REJECT">Reject</option>
                    <option value="REQUEST_INFORMATION">Request information</option>
                  </select>
                </label>

                <label>
                  <span>Reason</span>
                  <textarea
                    name="reason"
                    data-testid="decision-reason"
                    [(ngModel)]="reason"
                    rows="4"
                    maxlength="1000"
                    [class.invalid-field]="decisionErrorMsg()"
                    [attr.aria-invalid]="decisionErrorMsg() ? 'true' : 'false'"
                    [attr.aria-describedby]="decisionErrorMsg() ? 'decision-reason-error' : null"
                    [disabled]="savingDecision()"
                  ></textarea>
                </label>

                @if (decisionErrorMsg()) {
                  <div id="decision-reason-error" class="decision-error">{{ decisionErrorMsg() }}</div>
                }

                <div class="decision-actions">
                  <button type="submit" data-testid="decision-submit" [disabled]="savingDecision()">
                    {{ savingDecision() ? 'Saving decision' : 'Save decision' }}
                  </button>
                </div>
              </form>
            </section>
          }

          <section class="detail-section detail-section-wide">
            <h2>Description</h2>
            <p>{{ data.description || 'None' }}</p>
          </section>
        </div>
      }
    </section>
  `,
  styles: [`
    .admin-business-detail {
      display: grid;
      gap: 1rem;
    }

    .page-header {
      display: flex;
      align-items: end;
      justify-content: space-between;
      gap: 1rem;
    }

    .page-header a {
      color: var(--color-accent);
      font-size: 0.8125rem;
      font-weight: 700;
      text-decoration: none;
    }

    .page-header h1 {
      margin: 0.25rem 0 0;
      color: var(--color-text-primary);
      font-family: var(--font-display);
      font-size: 1.5rem;
      letter-spacing: 0;
    }

    .header-meta {
      display: flex;
      align-items: center;
      gap: 0.65rem;
      color: var(--color-text-muted);
      font-size: 0.8125rem;
      font-weight: 700;
    }

    .state-panel,
    .detail-section {
      border: 1px solid var(--color-border);
      border-radius: var(--radius-md);
      background: rgba(18, 19, 23, 0.88);
    }

    .state-panel {
      align-items: center;
      color: var(--color-text-secondary);
      display: flex;
      gap: 0.85rem;
      justify-content: space-between;
      min-height: 5.25rem;
      padding: 1rem;
    }

    .state-panel.error {
      color: var(--color-danger);
    }

    .state-panel strong {
      color: var(--color-text-primary);
      display: block;
      margin-bottom: 0.2rem;
    }

    .state-panel p {
      color: var(--color-text-secondary);
    }

    .state-panel-loading {
      justify-content: start;
    }

    .loading-dot {
      width: 0.75rem;
      height: 0.75rem;
      border-radius: 999px;
      background: var(--color-accent);
      box-shadow: 0 0 0 0 rgba(255, 159, 28, 0.45);
      flex: 0 0 auto;
    }

    .detail-grid {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 1rem;
    }

    .detail-section {
      padding: 1rem;
    }

    .detail-section-wide {
      grid-column: 1 / -1;
    }

    .detail-section h2 {
      margin: 0 0 0.85rem;
      color: var(--color-text-primary);
      font-size: 1rem;
      letter-spacing: 0;
    }

    dl {
      display: grid;
      gap: 0.75rem;
      margin: 0;
    }

    dt {
      color: var(--color-text-muted);
      font-size: 0.75rem;
      font-weight: 700;
      margin-bottom: 0.2rem;
    }

    dd,
    p {
      margin: 0;
      color: var(--color-text-primary);
      overflow-wrap: anywhere;
    }

    .status-pill {
      display: inline-flex;
      border-radius: var(--radius-md);
      background: var(--color-accent-muted);
      color: var(--color-accent);
      font-size: 0.75rem;
      font-weight: 700;
      padding: 0.35rem 0.55rem;
      white-space: nowrap;
    }

    .decision-form {
      display: grid;
      gap: 0.85rem;
      max-width: 760px;
    }

    .decision-form label {
      display: grid;
      gap: 0.35rem;
    }

    .decision-form label span {
      color: var(--color-text-muted);
      font-size: 0.75rem;
      font-weight: 700;
    }

    select,
    textarea {
      border: 1px solid var(--color-border);
      border-radius: var(--radius-md);
      background: var(--color-bg-primary);
      color: var(--color-text-primary);
      font: inherit;
      padding: 0.65rem 0.75rem;
    }

    textarea {
      min-height: 7rem;
      resize: vertical;
    }

    .invalid-field {
      border-color: var(--color-danger);
      box-shadow: 0 0 0 1px rgba(255, 78, 78, 0.28);
    }

    .decision-error {
      color: var(--color-danger);
      font-weight: 700;
    }

    .decision-actions {
      display: flex;
      justify-content: flex-end;
    }

    button {
      border: 1px solid transparent;
      border-radius: var(--radius-md);
      background: var(--color-accent);
      color: var(--color-bg-primary);
      cursor: pointer;
      font: inherit;
      font-weight: 800;
      padding: 0.7rem 1rem;
    }

    .secondary-btn {
      background: transparent;
      border-color: var(--color-border);
      color: var(--color-text-primary);
    }

    button:disabled {
      cursor: not-allowed;
      opacity: 0.65;
    }

    @media (max-width: 860px) {
      .page-header {
        align-items: start;
        flex-direction: column;
      }

      .detail-grid {
        grid-template-columns: 1fr;
      }

      .state-panel {
        align-items: stretch;
        flex-direction: column;
      }
    }
  `],
})
export class AdminBusinessApplicationDetailComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly businessApplicationService = inject(BusinessApplicationService);
  private readonly toastService = inject(ToastService);

  readonly loading = signal(true);
  readonly errorMsg = signal('');
  readonly decisionErrorMsg = signal('');
  readonly savingDecision = signal(false);
  readonly application = signal<BusinessApplication | null>(null);
  decision: BusinessApplicationDecision = 'APPROVE';
  reason = '';

  ngOnInit(): void {
    this.loadApplication();
  }

  loadApplication(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (!id) {
      this.errorMsg.set('Business application could not be loaded.');
      this.loading.set(false);
      return;
    }

    this.loading.set(true);
    this.errorMsg.set('');
    this.businessApplicationService.getAdminApplication(id).subscribe({
      next: response => {
        this.application.set(response.data);
        this.loading.set(false);
      },
      error: () => {
        this.errorMsg.set('Business application could not be loaded.');
        this.loading.set(false);
      },
    });
  }

  canDecide(application: BusinessApplication): boolean {
    return application.status === 'PENDING_VERIFICATION' || application.status === 'UNDER_REVIEW';
  }

  // Persists a platform admin decision using the loaded aggregate version for optimistic locking.
  submitDecision(): void {
    const application = this.application();
    if (!application || !this.canDecide(application)) {
      this.decisionErrorMsg.set('Only pending or under-review applications can receive a decision.');
      return;
    }

    const reason = this.reason.trim().replace(/\s+/g, ' ');
    if (!reason) {
      this.decisionErrorMsg.set('Decision reason is required.');
      return;
    }

    const decisionLabel = this.decision.toLowerCase().replace('_', ' ');
    if (!window.confirm(`Confirm ${decisionLabel} decision for ${application.legalName}?`)) {
      return;
    }

    this.savingDecision.set(true);
    this.decisionErrorMsg.set('');
    this.businessApplicationService.decide(application.id, {
      decision: this.decision,
      reason,
    }, application.version).subscribe({
      next: response => {
        this.application.set(response.data);
        this.reason = '';
        this.savingDecision.set(false);
        this.toastService.success('Business application decision saved.');
      },
      error: error => {
        this.savingDecision.set(false);
        this.decisionErrorMsg.set(this.decisionError(error));
      },
    });
  }

  private decisionError(error: { status?: number }): string {
    if (error.status === 409) {
      return 'The application changed. Refresh and try again.';
    }
    if (error.status === 403) {
      return 'You need platform admin access for this action.';
    }
    if (error.status === 404) {
      return 'Business application was not found.';
    }
    return 'Business application decision could not be saved.';
  }
}
