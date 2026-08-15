import { DatePipe } from '@angular/common';
import { Component, EventEmitter, Input, OnChanges, Output, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { forkJoin } from 'rxjs';
import {
  AdminListingEnforcementDetail,
  ListingEnforcementAction,
  ListingEnforcementActionType,
  ListingEnforcementCreateRequest,
  ListingEnforcementPreview,
  ListingEnforcementRevokeRequest,
  ListingEnforcementScope,
  ListingEnforcementTimelineEntry,
} from '../../core/models/listing.model';
import { AdminService } from '../../core/services/admin.service';
import { ListingService } from '../../core/services/listing.service';
import { ToastService } from '../../core/services/toast.service';
import { listingEnforcementCapabilities } from './listing-enforcement-capability';

@Component({
  selector: 'app-admin-listing-enforcement',
  standalone: true,
  imports: [DatePipe, FormsModule],
  template: `
    <section class="enforcement-workbench" aria-labelledby="listing-enforcement-heading">
      <header>
        <div>
          <p class="eyebrow">Policy ledger</p>
          <h2 id="listing-enforcement-heading">Listing enforcement</h2>
          <p class="intro">Temporarily restrict public visibility or new purchases without changing moderation state.</p>
        </div>
        @if (detail(); as policy) {
          <div class="boundary-state" aria-label="Current listing capability state">
            <span [class.blocked]="!policy.publicVisibilityAllowed">
              Visibility {{ policy.publicVisibilityAllowed ? 'open' : 'blocked' }}
            </span>
            <span [class.blocked]="!policy.purchasabilityAllowed">
              Purchases {{ policy.purchasabilityAllowed ? 'open' : 'blocked' }}
            </span>
          </div>
        }
      </header>

      @if (loading()) {
        <p class="state" role="status">Loading listing policy ledger...</p>
      } @else if (errorMsg()) {
        <div class="state error" role="alert">
          <span>{{ errorMsg() }}</span>
          <button type="button" class="secondary" (click)="load()">Retry</button>
        </div>
      } @else if (detail(); as policy) {
        @if (capabilities(policy).readOnlyReason) {
          <p class="read-only" role="status">{{ capabilities(policy).readOnlyReason }}</p>
        }

        <div class="ledger-grid">
          <section class="ledger-column">
            <h3>Active controls <span>{{ policy.activeEnforcementActions.length }}</span></h3>
            @if (policy.activeEnforcementActions.length === 0) {
              <p class="empty">No temporary listing controls are active.</p>
            }
            @for (action of policy.activeEnforcementActions; track action.enforcementActionId) {
              <article class="action-card active-card">
                <div class="action-heading">
                  <strong>{{ action.actionType }}</strong>
                  <span>v{{ action.version }}</span>
                </div>
                <p>{{ scopeLabels(action.scopes) }}</p>
                <small>Applied {{ action.createdAt | date: 'medium' }}</small>
                <small>{{ action.expiresAt ? ('Expires ' + (action.expiresAt | date: 'medium')) : 'Indefinite' }}</small>
                <p class="reason">{{ action.reasonCode }} · {{ action.reason }}</p>
                @if (capabilities(policy).canReinstate) {
                  <button type="button" class="secondary" [disabled]="busy()" (click)="previewReinstate(action)">
                    Preview reinstatement
                  </button>
                }
              </article>
            }
          </section>

          <section class="ledger-column history">
            <h3>History <span>{{ policy.historicalEnforcementActions.length }}</span></h3>
            @if (policy.historicalEnforcementActions.length === 0) {
              <p class="empty">No prior listing controls.</p>
            }
            @for (action of policy.historicalEnforcementActions; track action.enforcementActionId) {
              <article class="action-card">
                <div class="action-heading">
                  <strong>{{ action.actionType }}</strong>
                  <span>{{ action.lifecycleState }}</span>
                </div>
                <p>{{ scopeLabels(action.scopes) }}</p>
                <small>{{ action.createdAt | date: 'medium' }}</small>
              </article>
            }
          </section>
        </div>

        @if (capabilities(policy).canApply) {
          <form class="action-form" (ngSubmit)="previewApply()">
            <div class="form-heading">
              <div>
                <p class="eyebrow">Decision workbench</p>
                <h3>Apply temporary enforcement</h3>
              </div>
              <span>Listing v{{ policy.listingVersion }}</span>
            </div>

            <div class="form-grid">
              <label>
                <span>Action</span>
                <select name="actionType" [(ngModel)]="actionType" (ngModelChange)="actionChanged()" [disabled]="busy()">
                  <option value="RESTRICT">Restrict selected capability</option>
                  <option value="SUSPEND">Suspend listing capabilities</option>
                </select>
              </label>
              <label>
                <span>Reason code</span>
                <input name="reasonCode" [(ngModel)]="reasonCode" (ngModelChange)="invalidatePreview()"
                  maxlength="64" placeholder="POLICY_VIOLATION" [disabled]="busy()" />
              </label>
            </div>

            <fieldset>
              <legend>Operational scopes</legend>
              <label class="check">
                <input type="checkbox" name="visibility" [(ngModel)]="visibility"
                  (ngModelChange)="invalidatePreview()" [disabled]="busy() || actionType === 'SUSPEND'" />
                Public visibility
              </label>
              <label class="check">
                <input type="checkbox" name="purchasability" [(ngModel)]="purchasability"
                  (ngModelChange)="invalidatePreview()" [disabled]="busy() || actionType === 'SUSPEND'" />
                New purchases
              </label>
              @if (actionType === 'SUSPEND') {
                <p class="form-note">Suspension always applies both scopes. Existing orders and listing content are preserved.</p>
              }
            </fieldset>

            <label>
              <span>Staff reason</span>
              <textarea name="reason" rows="3" maxlength="1000" [(ngModel)]="reason"
                (ngModelChange)="invalidatePreview()" [disabled]="busy()"></textarea>
            </label>

            <div class="form-grid">
              <label>
                <span>Expires at (optional)</span>
                <input type="datetime-local" name="expiresAt" [(ngModel)]="expiresAt"
                  (ngModelChange)="expiryChanged()" [disabled]="busy() || indefiniteAcknowledged" />
              </label>
              <label class="check acknowledge">
                <input type="checkbox" name="indefinite" [(ngModel)]="indefiniteAcknowledged"
                  (ngModelChange)="indefiniteChanged()" [disabled]="busy()" />
                I acknowledge this action has no automatic expiry
              </label>
            </div>

            @if (formError()) {
              <p class="form-error" role="alert">{{ formError() }}</p>
            }
            <button type="submit" class="primary" [disabled]="busy()">
              {{ busy() ? 'Checking impact...' : 'Preview impact' }}
            </button>
          </form>
        }

        @if (preview(); as result) {
          <section class="preview" aria-labelledby="listing-enforcement-preview-heading">
            <p class="eyebrow">Required confirmation</p>
            <h3 id="listing-enforcement-preview-heading">Predicted boundary impact</h3>
            <div class="preview-boundaries">
              <span [class.blocked]="!result.predictedPublicVisibility">
                Public visibility {{ result.predictedPublicVisibility ? 'open' : 'blocked' }}
              </span>
              <span [class.blocked]="!result.predictedPurchasability">
                Purchasability {{ result.predictedPurchasability ? 'open' : 'blocked' }}
              </span>
            </div>
            <ul>
              @for (impact of result.impactSummary; track impact) { <li>{{ impact }}</li> }
              @for (warning of result.warnings; track warning) { <li class="warning">{{ warning }}</li> }
            </ul>
            <div class="preview-actions">
              <button type="button" class="secondary" (click)="cancelPreview()" [disabled]="busy()">Cancel</button>
              <button type="button" class="danger" (click)="confirmPreview()" [disabled]="busy()">
                {{ previewMode() === 'REINSTATE' ? 'Confirm reinstatement' : 'Confirm enforcement' }}
              </button>
            </div>
          </section>
        }

        <section class="timeline">
          <h3>Enforcement audit timeline</h3>
          @if (timeline().length === 0) { <p class="empty">No enforcement events.</p> }
          @for (entry of timeline(); track entry.eventId) {
            <article>
              <span class="timeline-marker"></span>
              <div>
                <div class="action-heading">
                  <strong>{{ entry.eventType }}</strong>
                  <time>{{ entry.occurredAt | date: 'medium' }}</time>
                </div>
                <p>{{ entry.actionType }} · {{ scopeLabels(entry.scopes) }}</p>
                <small>{{ entry.actorDisplayName || entry.actorId || 'System' }} · {{ entry.reasonCode }}</small>
                @if (entry.reason) { <p class="reason">{{ entry.reason }}</p> }
              </div>
            </article>
          }
        </section>
      }
    </section>
  `,
  styles: [`
    :host { display: block; grid-column: 1 / -1; }
    .enforcement-workbench { border: 1px solid #263746; border-radius: 16px; background: #101b24; color: #e8f2f6; padding: 1.2rem; }
    header, .action-heading, .form-heading, .preview-actions { display: flex; justify-content: space-between; gap: 1rem; align-items: flex-start; }
    h2, h3, p { margin-top: 0; } h2 { margin-bottom: .35rem; } h3 { margin-bottom: .75rem; }
    .eyebrow { margin-bottom: .25rem; color: #72d5e5; font-size: .72rem; font-weight: 800; letter-spacing: .14em; text-transform: uppercase; }
    .intro, small, .empty, .form-note { color: #9eb2bd; }
    .boundary-state, .preview-boundaries { display: flex; flex-wrap: wrap; gap: .45rem; }
    .boundary-state span, .preview-boundaries span { border: 1px solid #31505e; border-radius: 999px; padding: .35rem .65rem; color: #8ee5c1; background: #102b29; font-size: .78rem; white-space: nowrap; }
    .boundary-state .blocked, .preview-boundaries .blocked { color: #ffb3a8; border-color: #72463f; background: #321d1c; }
    .ledger-grid { display: grid; grid-template-columns: 1.25fr .75fr; gap: 1rem; margin: 1rem 0; }
    .ledger-column { border-top: 1px solid #2a3d49; padding-top: 1rem; } .ledger-column h3 span { color: #72d5e5; font-size: .8rem; }
    .action-card { display: grid; gap: .35rem; padding: .8rem; margin-bottom: .65rem; border: 1px solid #2a3d49; border-radius: 10px; background: #15232d; }
    .active-card { border-left: 3px solid #66d1e1; } .action-card p { margin-bottom: 0; }
    .reason { color: #c8d6dc; font-size: .86rem; }
    .action-form, .preview { margin-top: 1rem; border: 1px solid #36505d; border-radius: 12px; background: #142630; padding: 1rem; }
    .form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: .8rem; }
    label { display: grid; gap: .35rem; margin-bottom: .8rem; color: #cbd9df; font-size: .85rem; }
    input, select, textarea { width: 100%; box-sizing: border-box; border: 1px solid #3b515d; border-radius: 8px; padding: .68rem; color: #edf7fa; background: #0d1820; }
    fieldset { margin: 0 0 .8rem; border: 1px solid #304550; border-radius: 8px; } legend { color: #cbd9df; }
    .check { display: flex; align-items: center; gap: .55rem; } .check input { width: auto; } .acknowledge { align-self: center; margin-top: 1.25rem; }
    .read-only, .state, .form-error { padding: .75rem; border-radius: 8px; background: #202f38; }
    .error, .form-error { color: #ffc2b9; border: 1px solid #7a4941; } .warning { color: #ffd590; }
    button { border-radius: 8px; padding: .58rem .8rem; font-weight: 750; cursor: pointer; }
    button:disabled { opacity: .55; cursor: wait; } .primary { border: 0; background: #65d0e0; color: #082029; }
    .secondary { border: 1px solid #49616c; background: transparent; color: #dce9ee; }
    .danger { border: 1px solid #cc7567; background: #8f3f34; color: white; }
    .timeline { margin-top: 1rem; padding-top: 1rem; border-top: 1px solid #2a3d49; }
    .timeline article { display: grid; grid-template-columns: 12px 1fr; gap: .65rem; padding: .65rem 0; }
    .timeline-marker { width: 8px; height: 8px; margin-top: .35rem; border-radius: 50%; background: #68d4e4; box-shadow: 0 0 0 4px #1a3640; }
    time { color: #9eb2bd; font-size: .76rem; }
    @media (max-width: 800px) { .ledger-grid, .form-grid { grid-template-columns: 1fr; } header { flex-direction: column; } .acknowledge { margin-top: 0; } }
  `],
})
export class AdminListingEnforcementComponent implements OnChanges {
  @Input({ required: true }) listingId = '';
  @Output() readonly enforcementChanged = new EventEmitter<void>();

  private readonly listings = inject(ListingService);
  private readonly admin = inject(AdminService);
  private readonly toasts = inject(ToastService);
  readonly detail = signal<AdminListingEnforcementDetail | null>(null);
  readonly timeline = signal<ListingEnforcementTimelineEntry[]>([]);
  readonly preview = signal<ListingEnforcementPreview | null>(null);
  readonly previewMode = signal<'APPLY' | 'REINSTATE'>('APPLY');
  readonly loading = signal(false);
  readonly busy = signal(false);
  readonly errorMsg = signal('');
  readonly formError = signal('');
  actionType: ListingEnforcementActionType = 'RESTRICT';
  visibility = true;
  purchasability = false;
  reasonCode = 'POLICY_VIOLATION';
  reason = '';
  expiresAt = '';
  indefiniteAcknowledged = false;
  private pendingReinstatement: ListingEnforcementAction | null = null;

  ngOnChanges(): void { if (this.listingId) this.load(); }

  load(): void {
    this.loading.set(true); this.errorMsg.set(''); this.preview.set(null);
    forkJoin({ detail: this.listings.getAdminListingEnforcement(this.listingId),
      timeline: this.listings.getAdminListingEnforcementTimeline(this.listingId) }).subscribe({
      next: response => { this.detail.set(response.detail); this.timeline.set(response.timeline.entries); this.loading.set(false); },
      error: () => { this.loading.set(false); this.errorMsg.set('Listing enforcement controls could not be loaded.'); },
    });
  }

  capabilities(detail: AdminListingEnforcementDetail) { return listingEnforcementCapabilities(detail, this.admin); }
  scopeLabels(scopes: ListingEnforcementScope[]): string { return scopes.map(scope => scope === 'LISTING_PUBLIC_VISIBILITY' ? 'Public visibility' : 'New purchases').join(' · '); }

  actionChanged(): void {
    if (this.actionType === 'SUSPEND') { this.visibility = true; this.purchasability = true; }
    this.invalidatePreview();
  }
  expiryChanged(): void { if (this.expiresAt) this.indefiniteAcknowledged = false; this.invalidatePreview(); }
  indefiniteChanged(): void { if (this.indefiniteAcknowledged) this.expiresAt = ''; this.invalidatePreview(); }
  invalidatePreview(): void { this.preview.set(null); this.formError.set(''); }
  cancelPreview(): void { this.preview.set(null); this.pendingReinstatement = null; }

  previewApply(): void {
    const request = this.createRequest(false);
    if (!request) return;
    this.busy.set(true); this.previewMode.set('APPLY'); this.pendingReinstatement = null;
    this.listings.previewAdminListingEnforcement(this.listingId, request).subscribe({
      next: result => { this.preview.set(result); this.busy.set(false); },
      error: error => this.commandError(error, 'Enforcement impact could not be previewed.'),
    });
  }

  previewReinstate(action: ListingEnforcementAction): void {
    const reason = window.prompt('Staff reason for reinstatement');
    if (!reason?.trim()) return;
    this.reason = reason.trim(); this.pendingReinstatement = action; this.previewMode.set('REINSTATE'); this.busy.set(true);
    this.listings.previewAdminListingReinstatement(this.listingId, action.enforcementActionId,
      this.revokeRequest(action, false)).subscribe({
      next: result => { this.preview.set(result); this.busy.set(false); },
      error: error => this.commandError(error, 'Reinstatement impact could not be previewed.'),
    });
  }

  confirmPreview(): void {
    const current = this.detail(); if (!current || !this.preview()) return;
    this.busy.set(true);
    if (this.previewMode() === 'REINSTATE' && this.pendingReinstatement) {
      const action = this.pendingReinstatement;
      this.listings.reinstateAdminListing(this.listingId, action.enforcementActionId,
        this.revokeRequest(action, true)).subscribe({ next: () => this.completed('Listing reinstated.'),
          error: error => this.commandError(error, 'Listing could not be reinstated.') });
      return;
    }
    const request = this.createRequest(true); if (!request) { this.busy.set(false); return; }
    this.listings.createAdminListingEnforcement(this.listingId, request).subscribe({
      next: () => this.completed('Listing enforcement applied.'),
      error: error => this.commandError(error, 'Listing enforcement could not be applied.'),
    });
  }

  private createRequest(commit: boolean): ListingEnforcementCreateRequest | null {
    const current = this.detail();
    const scopes: ListingEnforcementScope[] = this.actionType === 'SUSPEND'
      ? ['LISTING_PUBLIC_VISIBILITY', 'LISTING_PURCHASABILITY']
      : [this.visibility ? 'LISTING_PUBLIC_VISIBILITY' : null,
        this.purchasability ? 'LISTING_PURCHASABILITY' : null].filter((scope): scope is ListingEnforcementScope => !!scope);
    if (!current || scopes.length === 0 || !this.reasonCode.trim() || !this.reason.trim()) {
      this.formError.set('Choose at least one scope and provide a reason code and staff reason.'); return null;
    }
    if (!this.expiresAt && !this.indefiniteAcknowledged) {
      this.formError.set('Set an expiry or explicitly acknowledge indefinite enforcement.'); return null;
    }
    return { actionType: this.actionType, scopes, reasonCode: this.reasonCode.trim().toUpperCase(),
      reason: this.reason.trim(), effectiveAt: null, expiresAt: this.expiresAt ? new Date(this.expiresAt).toISOString() : null,
      expectedListingVersion: current.listingVersion, idempotencyKey: commit ? crypto.randomUUID() : null, safeMetadata: {} };
  }

  private revokeRequest(action: ListingEnforcementAction, commit: boolean): ListingEnforcementRevokeRequest {
    return { expectedEnforcementVersion: action.version, reasonCode: 'REINSTATED_AFTER_REVIEW', reason: this.reason,
      idempotencyKey: commit ? crypto.randomUUID() : null, safeMetadata: {} };
  }

  private completed(message: string): void {
    this.busy.set(false); this.preview.set(null); this.pendingReinstatement = null; this.reason = '';
    this.toasts.success(message); this.load(); this.enforcementChanged.emit();
  }

  private commandError(error: any, fallback: string): void {
    this.busy.set(false);
    if (error?.status === 409) { this.formError.set('This listing changed. The current policy ledger has been reloaded.'); this.load(); return; }
    this.formError.set(error?.error?.error?.message || fallback);
  }
}
