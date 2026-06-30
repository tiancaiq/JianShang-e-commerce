import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { BusinessApplication } from '../../core/models/business-application.model';
import { BusinessApplicationService } from '../../core/services/business-application.service';

type ReviewQueueStatus = 'PENDING_VERIFICATION' | 'UNDER_REVIEW';

@Component({
  selector: 'app-admin-business-application-queue',
  standalone: true,
  imports: [FormsModule, RouterLink],
  template: `
    <section class="admin-business-queue">
      <header class="page-header">
        <div>
          <h1>Business Applications</h1>
          <p>Review submitted business seller applications.</p>
        </div>

        <label class="status-filter">
          <span>Status</span>
          @if (loading()) {
            <select name="statusFilter" disabled>
              <option [ngValue]="null">All reviewable</option>
              <option value="PENDING_VERIFICATION">Pending verification</option>
              <option value="UNDER_REVIEW">Under review</option>
            </select>
          } @else {
            <select name="statusFilter" [(ngModel)]="statusFilter" (ngModelChange)="loadQueue()">
              <option [ngValue]="null">All reviewable</option>
              <option value="PENDING_VERIFICATION">Pending verification</option>
              <option value="UNDER_REVIEW">Under review</option>
            </select>
          }
        </label>
      </header>

      @if (loading()) {
        <div class="state-panel state-panel-loading" aria-live="polite">
          <span class="loading-dot"></span>
          <div>
            <strong>Loading business applications...</strong>
            <p>Refreshing the review queue.</p>
          </div>
        </div>
      } @else if (errorMsg()) {
        <div class="state-panel error">
          <div>
            <strong>{{ errorMsg() }}</strong>
            <p>Try again to reload the current business review queue.</p>
          </div>
          <button type="button" class="secondary-btn" data-testid="queue-retry" (click)="loadQueue()" [disabled]="loading()">
            Retry
          </button>
        </div>
      } @else if (applications().length === 0) {
        <div class="state-panel">
          <div>
            <strong>No business applications are waiting for review.</strong>
            <p>Change the status filter or check back after new submissions arrive.</p>
          </div>
        </div>
      } @else {
        <div class="queue-table" role="table" aria-label="Business application review queue">
          <div class="queue-row queue-head" role="row">
            <span role="columnheader">Business</span>
            <span role="columnheader">Type</span>
            <span role="columnheader">Status</span>
            <span role="columnheader">Applicant</span>
            <span role="columnheader">Submitted</span>
          </div>

          @for (application of applications(); track application.id) {
            <a class="queue-row queue-link" role="row" [routerLink]="['/admin/business-applications', application.id]">
              <span role="cell">
                <strong>{{ application.legalName }}</strong>
                <small>{{ application.id }}</small>
              </span>
              <span role="cell">{{ application.businessType }} / {{ application.country }}</span>
              <span role="cell">
                <span class="status-pill">{{ statusLabel(application.status) }}</span>
              </span>
              <span role="cell">
                <strong>{{ application.contactEmail }}</strong>
                <small>{{ application.applicantUserId }}</small>
              </span>
              <span role="cell">{{ submittedLabel(application) }}</span>
            </a>
          }
        </div>
      }
    </section>
  `,
  styles: [`
    .admin-business-queue {
      display: grid;
      gap: 1rem;
    }

    .page-header {
      display: flex;
      align-items: end;
      justify-content: space-between;
      gap: 1rem;
    }

    .page-header h1 {
      margin: 0;
      color: var(--color-text-primary);
      font-family: var(--font-display);
      font-size: 1.5rem;
      letter-spacing: 0;
    }

    .page-header p {
      margin: 0.25rem 0 0;
      color: var(--color-text-secondary);
    }

    .status-filter {
      display: grid;
      gap: 0.35rem;
      min-width: 220px;
    }

    .status-filter span {
      color: var(--color-text-muted);
      font-size: 0.75rem;
      font-weight: 700;
    }

    select {
      border: 1px solid var(--color-border);
      border-radius: var(--radius-md);
      background: var(--color-bg-primary);
      color: var(--color-text-primary);
      padding: 0.65rem 0.75rem;
      font: inherit;
    }

    .state-panel,
    .queue-table {
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
      margin: 0;
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

    .secondary-btn {
      border: 1px solid var(--color-border);
      border-radius: var(--radius-md);
      background: transparent;
      color: var(--color-text-primary);
      cursor: pointer;
      font: inherit;
      font-weight: 800;
      padding: 0.7rem 1rem;
    }

    .secondary-btn:disabled {
      cursor: not-allowed;
      opacity: 0.65;
    }

    .queue-table {
      overflow: hidden;
    }

    .queue-link {
      color: inherit;
      text-decoration: none;
    }

    .queue-link:hover {
      background: rgba(255, 255, 255, 0.035);
    }

    .queue-row {
      display: grid;
      grid-template-columns: minmax(180px, 1.5fr) minmax(120px, 0.8fr) minmax(150px, 0.8fr) minmax(180px, 1.4fr) minmax(150px, 0.8fr);
      gap: 0.75rem;
      align-items: center;
      padding: 0.9rem 1rem;
      border-top: 1px solid var(--color-border);
    }

    .queue-head {
      border-top: none;
      color: var(--color-text-muted);
      font-size: 0.75rem;
      font-weight: 700;
      text-transform: uppercase;
    }

    .queue-row span {
      min-width: 0;
      overflow-wrap: anywhere;
    }

    strong {
      display: block;
      color: var(--color-text-primary);
      font-weight: 700;
    }

    small {
      display: block;
      margin-top: 0.2rem;
      color: var(--color-text-muted);
      font-size: 0.75rem;
    }

    .status-pill {
      display: inline-flex;
      width: fit-content;
      border-radius: var(--radius-md);
      background: var(--color-accent-muted);
      color: var(--color-accent);
      font-size: 0.75rem;
      font-weight: 700;
      padding: 0.35rem 0.55rem;
      white-space: nowrap;
    }

    @media (max-width: 980px) {
      .page-header {
        align-items: stretch;
        flex-direction: column;
      }

      .status-filter {
        min-width: 0;
      }

      .queue-row,
      .queue-head {
        grid-template-columns: 1fr;
      }

      .queue-head {
        display: none;
      }

      .queue-row {
        align-items: start;
      }

      .state-panel {
        align-items: stretch;
        flex-direction: column;
      }
    }
  `],
})
export class AdminBusinessApplicationQueueComponent implements OnInit {
  private readonly businessApplicationService = inject(BusinessApplicationService);

  readonly loading = signal(true);
  readonly errorMsg = signal('');
  readonly applications = signal<BusinessApplication[]>([]);
  statusFilter: ReviewQueueStatus | null = null;

  ngOnInit(): void {
    this.loadQueue();
  }

  loadQueue(): void {
    this.loading.set(true);
    this.errorMsg.set('');

    this.businessApplicationService.listAdminReviewQueue(this.statusFilter).subscribe({
      next: response => {
        this.applications.set(response.data);
        this.loading.set(false);
      },
      error: () => {
        this.applications.set([]);
        this.errorMsg.set('Business applications could not be loaded.');
        this.loading.set(false);
      },
    });
  }

  submittedLabel(application: BusinessApplication): string {
    if (!application.submittedAt) {
      return 'Not submitted';
    }
    return new Intl.DateTimeFormat(undefined, {
      dateStyle: 'medium',
      timeStyle: 'short',
    }).format(new Date(application.submittedAt));
  }

  statusLabel(status: string): string {
    const label = status.toLowerCase().replace(/_/g, ' ');
    return label.charAt(0).toUpperCase() + label.slice(1);
  }
}
