import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, Inject, signal } from '@angular/core';
import {
  EmbeddingRequestBackfillStatus,
  VectorRebuildStatus,
} from '../../core/models/admin-search-maintenance.model';
import { AdminSearchMaintenanceService } from '../../core/services/admin-search-maintenance.service';
import { ADMIN_SEARCH_MAINTENANCE_ENABLED } from './admin-search-maintenance.capability';

type Confirmation =
  | 'legacy-rebuild'
  | 'start-backfill'
  | 'resume-backfill'
  | 'start-vector-rebuild'
  | 'catch-up-vector-rebuild'
  | 'promote-vector-rebuild'
  | 'recover-vector-rebuild'
  | null;

@Component({
  selector: 'app-admin-search-maintenance',
  standalone: true,
  imports: [DatePipe],
  template: `
    @if (enabled) {
      <section class="maintenance-page">
        <header class="page-header">
          <div>
            <p class="eyebrow">Derived search operations</p>
            <h2>Search maintenance</h2>
            <p>
              Rebuild search data from authoritative listings. These operations do not edit
              listings or Product database facts.
            </p>
          </div>
          <span class="safety-mark">Manual only</span>
        </header>

        <ol class="maintenance-rail" aria-label="Search maintenance steps">
          <li>
            <article class="operation-card">
              <div class="step-label"><span>1</span> Lexical index</div>
              <h3>Rebuild the V1 listing index</h3>
              <p>
                Replace the derived keyword-search projection with the current eligible
                public listings.
              </p>

              @if (legacyIndexedCount() !== null) {
                <div class="result-strip" role="status">
                  <span>Last completed result</span>
                  <strong>{{ legacyIndexedCount() }} listings indexed</strong>
                </div>
              }
              @if (legacyError()) {
                <p class="notice error" role="alert">{{ legacyError() }}</p>
              }

              @if (confirmation() === 'legacy-rebuild') {
                <div class="confirmation" role="group" aria-label="Confirm V1 listing index rebuild">
                  <p>
                    Confirm V1 rebuild. Product will reconstruct the derived keyword index
                    from current public listing data.
                  </p>
                  <div class="button-row">
                    <button
                      type="button"
                      class="primary"
                      [disabled]="busy()"
                      (click)="confirmLegacyRebuild()">
                      Confirm V1 rebuild
                    </button>
                    <button type="button" [disabled]="busy()" (click)="cancelConfirmation()">
                      Cancel
                    </button>
                  </div>
                </div>
              } @else {
                <button
                  type="button"
                  class="primary"
                  [disabled]="busy()"
                  (click)="reviewLegacyRebuild()">
                  {{ legacyPending() ? 'Rebuilding…' : 'Review V1 rebuild' }}
                </button>
              }
            </article>
          </li>

          <li>
            <article class="operation-card">
              <div class="step-label"><span>2</span> Embedding requests</div>
              <h3>Create missing listing embedding requests</h3>
              <p>
                Process one bounded page at a time. This creates reference-only work for
                eligible individual listings; it does not call a provider or create vectors.
              </p>

              @if (backfillStatus(); as status) {
                <section class="run-panel" aria-labelledby="backfill-run-heading">
                  <div class="run-heading">
                    <div>
                      <span id="backfill-run-heading">Current run</span>
                      <strong>{{ status.state }}</strong>
                    </div>
                    <span class="run-reference">Run {{ status.runId }}</span>
                  </div>
                  <dl>
                    <div><dt>Pages</dt><dd>{{ status.pageCount }}</dd></div>
                    <div><dt>Processed</dt><dd>{{ status.processedCount }}</dd></div>
                    <div><dt>Created</dt><dd>{{ status.createdCount }}</dd></div>
                    <div><dt>Already present</dt><dd>{{ status.alreadyPresentCount }}</dd></div>
                    <div><dt>Skipped</dt><dd>{{ status.skippedCount }}</dd></div>
                    <div><dt>Failed</dt><dd>{{ status.failedCount }}</dd></div>
                  </dl>
                  <p class="timestamp">
                    Updated {{ status.updatedAt | date:'medium' }}
                    @if (status.completedAt) {
                      · Completed {{ status.completedAt | date:'medium' }}
                    }
                  </p>
                </section>
              }

              @if (backfillError()) {
                <p class="notice error" role="alert">{{ backfillError() }}</p>
              }

              @if (confirmation() === 'start-backfill') {
                <div class="confirmation" role="group" aria-label="Confirm embedding request backfill start">
                  <p>
                    Confirm backfill start. Product will capture a finite listing watermark
                    and process the first bounded page.
                  </p>
                  <div class="button-row">
                    <button
                      type="button"
                      class="primary"
                      [disabled]="busy()"
                      (click)="confirmBackfillStart()">
                      Confirm backfill start
                    </button>
                    <button type="button" [disabled]="busy()" (click)="cancelConfirmation()">
                      Cancel
                    </button>
                  </div>
                </div>
              } @else if (confirmation() === 'resume-backfill') {
                <div class="confirmation" role="group" aria-label="Confirm one backfill page">
                  <p>
                    Confirm one-page continuation for the current server-owned run. This
                    action is never repeated automatically.
                  </p>
                  <div class="button-row">
                    <button
                      type="button"
                      class="primary"
                      [disabled]="busy()"
                      (click)="confirmBackfillResume()">
                      Confirm one page
                    </button>
                    <button type="button" [disabled]="busy()" (click)="cancelConfirmation()">
                      Cancel
                    </button>
                  </div>
                </div>
              } @else {
                <div class="button-row">
                  @if (!backfillStatus()) {
                    <button
                      type="button"
                      class="primary"
                      [disabled]="busy()"
                      (click)="reviewBackfillStart()">
                      {{ backfillPending() ? 'Starting…' : 'Review backfill start' }}
                    </button>
                  } @else {
                    @if (canResumeBackfill()) {
                      <button
                        type="button"
                        class="primary"
                        [disabled]="busy()"
                        (click)="reviewBackfillResume()">
                        {{ backfillPending() ? 'Continuing…' : 'Review next page' }}
                      </button>
                    }
                    <button
                      type="button"
                      [disabled]="busy()"
                      (click)="refreshBackfillStatus()">
                      {{ backfillPending() ? 'Loading…' : 'Refresh status' }}
                    </button>
                  }
                </div>
              }
            </article>
          </li>

          <li>
            <article class="operation-card">
              <div class="step-label"><span>3</span> V2 activation</div>
              <h3>Prepare, catch up, promote, or recover a V2 vector rebuild</h3>
              <p>
                Operate the Product-owned fenced V2 rebuild workflow. Command eligibility
                comes only from Product status booleans and every POST is manually confirmed.
              </p>

              @if (vectorStatus(); as status) {
                <section class="run-panel" aria-labelledby="vector-run-heading">
                  <div class="run-heading">
                    <div>
                      <span id="vector-run-heading">V2 rebuild run</span>
                      <strong>{{ status.state }}</strong>
                    </div>
                    <span class="run-reference">Run {{ status.runId }}</span>
                  </div>
                  <dl>
                    <div><dt>Authoritative documents</dt><dd>{{ status.authoritativeDocumentCount }}</dd></div>
                    <div><dt>Vector documents</dt><dd>{{ status.vectorDocumentCount }}</dd></div>
                    <div><dt>Catch-up work</dt><dd>{{ status.catchUpWorkCount }}</dd></div>
                    <div><dt>Deferred receipts</dt><dd>{{ status.deferredReceiptCount }}</dd></div>
                    <div><dt>Candidate role</dt><dd>{{ status.candidateRole }}</dd></div>
                    <div><dt>Previous role</dt><dd>{{ status.previousGenerationRole }}</dd></div>
                  </dl>
                  <div class="eligibility" aria-label="Server command eligibility">
                    <span [class.allowed]="status.canCatchUp">Catch-up {{ status.canCatchUp ? 'allowed' : 'blocked' }}</span>
                    <span [class.allowed]="status.canPromote">Promote {{ status.canPromote ? 'allowed' : 'blocked' }}</span>
                    <span [class.allowed]="status.canRecover">Recover {{ status.canRecover ? 'allowed' : 'blocked' }}</span>
                  </div>
                  <p class="timestamp">
                    Updated {{ status.updatedAt | date:'medium' }}
                    @if (status.promotedAt) {
                      · Promoted {{ status.promotedAt | date:'medium' }}
                    }
                  </p>
                </section>
              }

              @if (vectorError()) {
                <p class="notice error" role="alert">{{ vectorError() }}</p>
              }

              @if (confirmation() === 'start-vector-rebuild') {
                <div class="confirmation" role="group" aria-label="Confirm V2 vector rebuild prepare">
                  <p>
                    Confirm V2 prepare. Product will create and validate a server-owned
                    inactive V2 candidate without moving live aliases.
                  </p>
                  <div class="button-row">
                    <button
                      type="button"
                      class="primary"
                      [disabled]="busy()"
                      (click)="confirmVectorStart()">
                      Confirm V2 prepare
                    </button>
                    <button type="button" [disabled]="busy()" (click)="cancelConfirmation()">
                      Cancel
                    </button>
                  </div>
                </div>
              } @else if (confirmation() === 'catch-up-vector-rebuild') {
                <div class="confirmation" role="group" aria-label="Confirm V2 catch-up">
                  <p>
                    Confirm V2 catch-up. Product will advance the current server-owned run
                    only if the latest status still allows it.
                  </p>
                  <div class="button-row">
                    <button
                      type="button"
                      class="primary"
                      [disabled]="busy()"
                      (click)="confirmVectorCatchUp()">
                      Confirm V2 catch-up
                    </button>
                    <button type="button" [disabled]="busy()" (click)="cancelConfirmation()">
                      Cancel
                    </button>
                  </div>
                </div>
              } @else if (confirmation() === 'promote-vector-rebuild') {
                <div class="confirmation" role="group" aria-label="Confirm V2 promotion">
                  <p>
                    Confirm V2 promotion. Product will recheck the fence and alias
                    preconditions before any alias movement.
                  </p>
                  <div class="button-row">
                    <button
                      type="button"
                      class="primary"
                      [disabled]="busy()"
                      (click)="confirmVectorPromote()">
                      Confirm V2 promotion
                    </button>
                    <button type="button" [disabled]="busy()" (click)="cancelConfirmation()">
                      Cancel
                    </button>
                  </div>
                </div>
              } @else if (confirmation() === 'recover-vector-rebuild') {
                <div class="confirmation" role="group" aria-label="Confirm V2 recovery">
                  <p>
                    Confirm V2 recovery. Product will reconcile the durable run against
                    exact alias state without client topology input.
                  </p>
                  <div class="button-row">
                    <button
                      type="button"
                      class="primary"
                      [disabled]="busy()"
                      (click)="confirmVectorRecover()">
                      Confirm V2 recovery
                    </button>
                    <button type="button" [disabled]="busy()" (click)="cancelConfirmation()">
                      Cancel
                    </button>
                  </div>
                </div>
              } @else {
                <div class="button-row">
                  @if (!vectorStatus()) {
                    <button
                      type="button"
                      class="primary"
                      [disabled]="busy()"
                      (click)="reviewVectorStart()">
                      {{ vectorPending() ? 'Preparing...' : 'Review V2 prepare' }}
                    </button>
                  } @else {
                    <button
                      type="button"
                      [disabled]="busy()"
                      (click)="refreshVectorStatus()">
                      {{ vectorPending() ? 'Loading...' : 'Refresh V2 status' }}
                    </button>
                    <button
                      type="button"
                      class="primary"
                      [disabled]="busy() || !vectorStatus()?.canCatchUp"
                      (click)="reviewVectorCatchUp()">
                      Review V2 catch-up
                    </button>
                    <button
                      type="button"
                      class="primary"
                      [disabled]="busy() || !vectorStatus()?.canPromote"
                      (click)="reviewVectorPromote()">
                      Review V2 promotion
                    </button>
                    <button
                      type="button"
                      [disabled]="busy() || !vectorStatus()?.canRecover"
                      (click)="reviewVectorRecover()">
                      Review V2 recovery
                    </button>
                  }
                </div>
              }
            </article>
          </li>
        </ol>

        <p class="live-status" aria-live="polite" aria-atomic="true">
          {{ liveMessage() }}
        </p>
      </section>
    }
  `,
  styles: [`
    .maintenance-page {
      display: grid;
      gap: 1.25rem;
      max-width: 920px;
    }

    .page-header {
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      gap: 1.25rem;
      padding-bottom: 1rem;
      border-bottom: 1px solid var(--color-border);
    }

    .eyebrow,
    .step-label {
      color: var(--color-info);
      font-size: 0.75rem;
      font-weight: 800;
      letter-spacing: 0.08em;
      text-transform: uppercase;
    }

    h2 {
      margin: 0.2rem 0 0.45rem;
      font-size: 1.65rem;
      letter-spacing: 0;
    }

    .page-header p,
    .operation-card > p {
      max-width: 68ch;
      margin: 0;
      color: var(--color-text-secondary);
      line-height: 1.55;
    }

    .safety-mark {
      flex: none;
      padding: 0.35rem 0.65rem;
      border: 1px solid rgba(56, 189, 248, 0.42);
      border-radius: 999px;
      color: var(--color-info);
      font-size: 0.75rem;
      font-weight: 800;
      text-transform: uppercase;
    }

    .maintenance-rail {
      display: grid;
      gap: 1rem;
      margin: 0;
      padding: 0;
      list-style: none;
      counter-reset: operation;
    }

    .operation-card {
      display: grid;
      gap: 1rem;
      padding: 1.15rem;
      border: 1px solid var(--color-border);
      border-left: 4px solid var(--color-info);
      border-radius: var(--radius-lg);
      background: var(--color-bg-secondary);
    }

    .step-label {
      display: flex;
      align-items: center;
      gap: 0.5rem;
    }

    .step-label span {
      display: inline-grid;
      width: 1.65rem;
      height: 1.65rem;
      place-items: center;
      border: 1px solid rgba(56, 189, 248, 0.42);
      border-radius: 50%;
    }

    h3 {
      margin: -0.35rem 0 -0.5rem;
      font-size: 1.1rem;
      letter-spacing: 0;
    }

    button {
      min-height: 42px;
      padding: 0.6rem 0.9rem;
      border: 1px solid var(--color-border);
      border-radius: var(--radius-md);
      background: var(--color-bg-primary);
      color: var(--color-text-primary);
      cursor: pointer;
      font: inherit;
      font-weight: 750;
    }

    button.primary {
      border-color: var(--color-info);
      background: var(--color-info);
      color: #06131b;
    }

    button:focus-visible {
      outline: 3px solid rgba(56, 189, 248, 0.45);
      outline-offset: 2px;
    }

    button:disabled {
      cursor: not-allowed;
      opacity: 0.48;
    }

    .button-row {
      display: flex;
      flex-wrap: wrap;
      gap: 0.65rem;
    }

    .confirmation,
    .run-panel,
    .result-strip {
      padding: 0.9rem;
      border: 1px solid var(--color-border);
      border-radius: var(--radius-md);
      background: var(--color-bg-primary);
    }

    .confirmation {
      display: grid;
      gap: 0.8rem;
      border-color: rgba(56, 189, 248, 0.5);
    }

    .confirmation p,
    .timestamp,
    .notice {
      margin: 0;
      color: var(--color-text-secondary);
      line-height: 1.45;
    }

    .result-strip,
    .run-heading {
      display: flex;
      justify-content: space-between;
      gap: 1rem;
      align-items: center;
    }

    .eligibility {
      display: flex;
      flex-wrap: wrap;
      gap: 0.5rem;
    }

    .eligibility span {
      padding: 0.35rem 0.55rem;
      border: 1px solid var(--color-border);
      border-radius: 999px;
      color: var(--color-text-muted);
      font-size: 0.78rem;
      font-weight: 800;
    }

    .eligibility span.allowed {
      border-color: rgba(22, 163, 74, 0.38);
      color: var(--color-success);
    }

    .result-strip span,
    .run-heading span,
    dt,
    .timestamp {
      color: var(--color-text-muted);
      font-size: 0.8rem;
    }

    .run-panel {
      display: grid;
      gap: 0.85rem;
    }

    .run-heading > div {
      display: grid;
      gap: 0.2rem;
    }

    .run-reference {
      max-width: 18rem;
      overflow-wrap: anywhere;
      font-family: monospace;
    }

    dl {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: 0.6rem;
      margin: 0;
    }

    dl div {
      padding: 0.65rem;
      border: 1px solid var(--color-border);
      border-radius: var(--radius-md);
    }

    dt,
    dd {
      margin: 0;
    }

    dd {
      margin-top: 0.25rem;
      font-size: 1.1rem;
      font-weight: 800;
    }

    .notice.error {
      color: var(--color-danger);
    }

    .live-status {
      min-height: 1.25rem;
      margin: 0;
      color: var(--color-text-secondary);
    }

    @media (max-width: 680px) {
      .page-header,
      .result-strip,
      .run-heading {
        display: grid;
      }

      dl {
        grid-template-columns: repeat(2, minmax(0, 1fr));
      }
    }

    @media (prefers-reduced-motion: reduce) {
      * {
        scroll-behavior: auto;
      }
    }
  `],
})
export class AdminSearchMaintenanceComponent {
  readonly confirmation = signal<Confirmation>(null);
  readonly legacyPending = signal(false);
  readonly legacyIndexedCount = signal<number | null>(null);
  readonly legacyError = signal('');
  readonly backfillPending = signal(false);
  readonly backfillStatus = signal<EmbeddingRequestBackfillStatus | null>(null);
  readonly backfillError = signal('');
  readonly vectorPending = signal(false);
  readonly vectorStatus = signal<VectorRebuildStatus | null>(null);
  readonly vectorError = signal('');
  readonly liveMessage = signal('');

  constructor(
    private readonly service: AdminSearchMaintenanceService,
    @Inject(ADMIN_SEARCH_MAINTENANCE_ENABLED) readonly enabled: boolean,
  ) {}

  busy(): boolean {
    return this.legacyPending() || this.backfillPending() || this.vectorPending();
  }

  reviewLegacyRebuild(): void {
    if (!this.busy()) {
      this.confirmation.set('legacy-rebuild');
    }
  }

  confirmLegacyRebuild(): void {
    if (this.confirmation() !== 'legacy-rebuild' || this.busy()) {
      return;
    }
    this.confirmation.set(null);
    this.legacyPending.set(true);
    this.legacyError.set('');
    this.service.rebuildLegacyIndex().subscribe({
      next: result => {
        this.legacyIndexedCount.set(result.indexedCount);
        this.legacyPending.set(false);
        this.liveMessage.set(`V1 rebuild completed with ${result.indexedCount} listings indexed.`);
      },
      error: error => {
        this.legacyPending.set(false);
        this.legacyError.set(safeOperationError(error, 'V1 rebuild'));
        this.liveMessage.set('V1 rebuild did not complete.');
      },
    });
  }

  reviewBackfillStart(): void {
    if (!this.busy() && !this.backfillStatus()) {
      this.confirmation.set('start-backfill');
    }
  }

  confirmBackfillStart(): void {
    if (this.confirmation() !== 'start-backfill' || this.busy() || this.backfillStatus()) {
      return;
    }
    this.confirmation.set(null);
    this.runBackfillCommand(
      this.service.startEmbeddingRequestBackfill(),
      'Embedding request backfill started.',
    );
  }

  reviewBackfillResume(): void {
    if (!this.busy() && this.canResumeBackfill()) {
      this.confirmation.set('resume-backfill');
    }
  }

  confirmBackfillResume(): void {
    const status = this.backfillStatus();
    if (this.confirmation() !== 'resume-backfill'
      || this.busy()
      || !status
      || !this.canResumeBackfill()) {
      return;
    }
    this.confirmation.set(null);
    this.runBackfillCommand(
      this.service.resumeEmbeddingRequestBackfill(status.runId),
      'One bounded backfill page completed.',
    );
  }

  refreshBackfillStatus(): void {
    const status = this.backfillStatus();
    if (!status || this.busy()) {
      return;
    }
    this.runBackfillCommand(
      this.service.getEmbeddingRequestBackfill(status.runId),
      'Backfill status refreshed.',
    );
  }

  canResumeBackfill(): boolean {
    const state = this.backfillStatus()?.state;
    return state === 'PENDING' || state === 'FAILED';
  }

  reviewVectorStart(): void {
    if (!this.busy() && !this.vectorStatus()) {
      this.confirmation.set('start-vector-rebuild');
    }
  }

  confirmVectorStart(): void {
    if (this.confirmation() !== 'start-vector-rebuild' || this.busy() || this.vectorStatus()) {
      return;
    }
    this.confirmation.set(null);
    this.runVectorCommand(
      this.service.startVectorRebuild(),
      'V2 vector rebuild prepared.',
    );
  }

  reviewVectorCatchUp(): void {
    if (!this.busy() && this.vectorStatus()?.canCatchUp) {
      this.confirmation.set('catch-up-vector-rebuild');
    }
  }

  confirmVectorCatchUp(): void {
    const status = this.vectorStatus();
    if (this.confirmation() !== 'catch-up-vector-rebuild'
      || this.busy()
      || !status
      || !status.canCatchUp) {
      return;
    }
    this.confirmation.set(null);
    this.runVectorCommand(
      this.service.catchUpVectorRebuild(status.runId),
      'V2 catch-up completed.',
    );
  }

  reviewVectorPromote(): void {
    if (!this.busy() && this.vectorStatus()?.canPromote) {
      this.confirmation.set('promote-vector-rebuild');
    }
  }

  confirmVectorPromote(): void {
    const status = this.vectorStatus();
    if (this.confirmation() !== 'promote-vector-rebuild'
      || this.busy()
      || !status
      || !status.canPromote) {
      return;
    }
    this.confirmation.set(null);
    this.runVectorCommand(
      this.service.promoteVectorRebuild(status.runId),
      'V2 promotion completed.',
    );
  }

  reviewVectorRecover(): void {
    if (!this.busy() && this.vectorStatus()?.canRecover) {
      this.confirmation.set('recover-vector-rebuild');
    }
  }

  confirmVectorRecover(): void {
    const status = this.vectorStatus();
    if (this.confirmation() !== 'recover-vector-rebuild'
      || this.busy()
      || !status
      || !status.canRecover) {
      return;
    }
    this.confirmation.set(null);
    this.runVectorCommand(
      this.service.recoverVectorRebuild(status.runId),
      'V2 recovery completed.',
    );
  }

  refreshVectorStatus(): void {
    const status = this.vectorStatus();
    if (!status || this.busy()) {
      return;
    }
    this.runVectorCommand(
      this.service.getVectorRebuild(status.runId),
      'V2 rebuild status refreshed.',
    );
  }

  cancelConfirmation(): void {
    if (!this.busy()) {
      this.confirmation.set(null);
    }
  }

  private runBackfillCommand(
    operation: ReturnType<AdminSearchMaintenanceService['getEmbeddingRequestBackfill']>,
    successMessage: string,
  ): void {
    this.backfillPending.set(true);
    this.backfillError.set('');
    operation.subscribe({
      next: status => {
        this.backfillStatus.set(status);
        this.backfillPending.set(false);
        this.liveMessage.set(status.state === 'COMPLETED'
          ? 'Embedding request backfill completed.'
          : successMessage);
      },
      error: error => {
        this.backfillPending.set(false);
        this.backfillError.set(safeOperationError(error, 'Embedding request backfill'));
        this.liveMessage.set('Embedding request backfill did not complete.');
      },
    });
  }

  private runVectorCommand(
    operation: ReturnType<AdminSearchMaintenanceService['getVectorRebuild']>,
    successMessage: string,
  ): void {
    this.vectorPending.set(true);
    this.vectorError.set('');
    operation.subscribe({
      next: status => {
        this.vectorStatus.set(status);
        this.vectorPending.set(false);
        this.liveMessage.set(status.state === 'PROMOTED'
          ? 'V2 vector rebuild is promoted.'
          : successMessage);
      },
      error: error => {
        this.vectorPending.set(false);
        this.vectorError.set(safeOperationError(error, 'V2 vector rebuild'));
        this.liveMessage.set('V2 vector rebuild command did not complete.');
      },
    });
  }
}

function safeOperationError(error: unknown, operation: string): string {
  if (error instanceof HttpErrorResponse) {
    if (error.status === 401) {
      return 'Your admin session is no longer authenticated. Sign in again before retrying.';
    }
    if (error.status === 403) {
      return 'Platform administrator access is required for this operation.';
    }
    if (error.status === 404) {
      return `${operation} is disabled or the requested run is unavailable.`;
    }
    if (error.status === 409) {
      return `${operation} is busy or no longer accepts this command. Refresh status before retrying.`;
    }
    if (error.status === 503) {
      return `${operation} is temporarily unavailable. No automatic retry was attempted.`;
    }
  }
  return `${operation} returned an invalid or unavailable response. No automatic retry was attempted.`;
}
