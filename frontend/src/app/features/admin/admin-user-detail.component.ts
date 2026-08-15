import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import {
  AdminUserDetail,
  AdminUserTimelineEntry,
  CreateUserEnforcementRequest,
  EnforcementPreview,
  RevokeUserEnforcementRequest,
  UserEnforcementAction,
  UserEnforcementActionType,
  UserEnforcementScope,
} from '../../core/models/admin-user.model';
import { AdminUserService } from '../../core/services/admin-user.service';
import { AdminService } from '../../core/services/admin.service';
import { ADMIN_PERMISSIONS } from '../../core/security/admin-permissions';
import { canCreateUserEnforcement, userAdminCapabilities } from './user-admin-capability';

@Component({
  selector: 'app-admin-user-detail',
  standalone: true,
  imports: [FormsModule, RouterLink],
  template: `
    <section class="user-detail">
      <a routerLink="/admin/users" class="back">← User administration</a>
      @if (loading()) { <div class="panel">Loading user…</div> }
      @else if (error()) { <div class="panel error" role="alert">{{ error() }} <button (click)="load()">Retry</button></div> }
      @else if (user(); as current) {
        <header class="hero">
          <div><p>Marketplace account</p><h2>{{ current.safeDisplayName }}</h2><span>{{ current.userId }}</span></div>
          <div class="hero-state"><small>Strongest active action</small><strong>{{ current.strongestActiveAction || 'CLEAR' }}</strong></div>
        </header>

        @if (capabilities().readOnlyReason) { <div class="notice" role="status">{{ capabilities().readOnlyReason }}</div> }

        <div class="summary-grid">
          <article class="panel"><h3>Account summary</h3><dl>
            <div><dt>Email</dt><dd>{{ current.safeEmail || 'Not provided' }} @if (current.emailMasked) { <em>masked</em> }</dd></div>
            <div><dt>Identity provider</dt><dd>{{ current.accountReference }}</dd></div>
            <div><dt>Authentication</dt><dd>{{ current.authenticationState }}</dd></div>
            <div><dt>Account type</dt><dd>{{ current.accountType }}</dd></div>
            <div><dt>Platform admin</dt><dd>{{ current.platformAdmin ? 'Yes — marketplace-only enforcement' : 'No' }}</dd></div>
            <div><dt>Created</dt><dd>{{ formatDate(current.createdAt) }}</dd></div>
          </dl></article>
          <article class="panel"><h3>Seller &amp; business</h3><dl>
            <div><dt>Individual seller</dt><dd>{{ current.individualSellerProfile?.status || 'NOT ACTIVATED' }}</dd></div>
            <div><dt>Business memberships</dt><dd>{{ current.businessMemberships.length }}</dd></div>
          </dl>
          @for (membership of current.businessMemberships; track membership.businessId) {
            <div class="membership"><strong>{{ membership.businessDisplayName }}</strong><span>{{ membership.membershipRole }} · {{ membership.membershipStatus }} · business {{ membership.businessStatus }}</span></div>
          }</article>
        </div>

        <article class="panel ledger"><header><div><p>Capability ledger</p><h3>Effective marketplace capabilities</h3></div><span>Version {{ current.version }}</span></header>
          <div class="scope-grid">
            @for (scope of operationalScopes(); track scope) {
              <div class="scope-row"><span>{{ scopeLabel(scope) }}</span>
                @if (restrictionFor(scope); as restriction) { <strong class="blocked">Blocked · {{ restriction.actionType }}</strong> }
                @else { <strong class="allowed">Allowed</strong> }
              </div>
            }
            <div class="scope-row reserved"><span>Login</span><strong>Reserved · not enforced</strong></div>
            <div class="scope-row reserved"><span>Messaging</span><strong>Reserved · not enforced</strong></div>
          </div>
        </article>

        <div class="actions-layout">
          <article class="panel"><header><h3>Active enforcement</h3><span>{{ current.activeEnforcementActions.length }}</span></header>
            @if (!current.activeEnforcementActions.length) { <p class="muted">No active enforcement actions.</p> }
            @for (action of current.activeEnforcementActions; track action.enforcementActionId) {
              <div class="action-card"><div><strong>{{ action.actionType === 'BAN' ? 'Marketplace ban' : action.actionType }}</strong><span>{{ scopeList(action.scopes) }}</span><small>{{ action.reasonCode }} · {{ action.reason }}</small><small>Created {{ formatDate(action.createdAt) }} · {{ action.expiresAt ? 'until ' + formatDate(action.expiresAt) : 'indefinite' }}</small></div>
                @if (capabilities().canReinstate) { <button type="button" class="secondary" (click)="beginRevoke(action)">Reinstate</button> }
              </div>
            }
          </article>
          <article class="panel"><header><h3>Historical enforcement</h3><span>{{ current.historicalEnforcementActions.length }}</span></header>
            @if (!current.historicalEnforcementActions.length) { <p class="muted">No expired or revoked actions.</p> }
            @for (action of current.historicalEnforcementActions; track action.enforcementActionId) {
              <div class="history-row"><strong>{{ action.actionType }}</strong><span>{{ action.lifecycleState }} · {{ scopeList(action.scopes) }} · {{ action.reasonCode }}</span></div>
            }
          </article>
        </div>

        @if (!capabilities().isReadOnly) {
          <article class="panel action-workbench">
            <header><div><p>Administrative action</p><h3>Apply marketplace enforcement</h3></div></header>
            <div class="form-grid">
              <label>Action type<select [(ngModel)]="actionType" (ngModelChange)="actionTypeChanged()">
                <option value="RESTRICT" [disabled]="!capabilities().canRestrict">Restrict</option>
                <option value="SUSPEND" [disabled]="!capabilities().canSuspend">Suspend</option>
                <option value="BAN" [disabled]="!capabilities().canBan">Marketplace ban</option>
              </select></label>
              <fieldset><legend>Operational scopes</legend>
                <label><input type="checkbox" [(ngModel)]="buyingSelected" [disabled]="actionType === 'BAN'" (ngModelChange)="invalidatePreview()" /> Buying</label>
                <label><input type="checkbox" [(ngModel)]="sellingSelected" [disabled]="actionType === 'BAN'" (ngModelChange)="invalidatePreview()" /> Selling</label>
              </fieldset>
              <label>Reason code<input [(ngModel)]="reasonCode" (ngModelChange)="invalidatePreview()" placeholder="POLICY_VIOLATION" /></label>
              <label class="wide">Required explanation<textarea [(ngModel)]="reason" (ngModelChange)="invalidatePreview()" rows="3"></textarea></label>
              <label>Expiration (optional)<input type="datetime-local" [(ngModel)]="expiresAt" (ngModelChange)="invalidatePreview()" /></label>
              @if (actionType === 'SUSPEND' && !expiresAt) { <label class="confirm"><input type="checkbox" [(ngModel)]="indefiniteConfirmed" /> I confirm this suspension is indefinite.</label> }
            </div>
            @if (!canCreate()) { <p class="muted">Your permissions do not allow this action type.</p> }
            <div class="button-row"><button type="button" [disabled]="previewing() || !canPreview()" (click)="previewCreate()">{{ previewing() ? 'Checking…' : 'Preview action' }}</button></div>
            @if (actionError()) { <div class="inline-error" role="alert">{{ actionError() }}</div> }
            @if (createPreview(); as preview) {
              <section class="preview" aria-label="Enforcement preview"><h4>Dry-run preview</h4>
                <p><strong>{{ preview.proposedAction.actionType }}</strong> will explicitly affect {{ scopeList(preview.proposedAction.scopes) }}.</p>
                <p>{{ preview.permanent ? 'This action is indefinite.' : 'This action expires at ' + formatDate(preview.proposedAction.expiresAt!) }}</p>
                <p>Predicted effective restrictions: {{ effectiveList(preview) }}</p>
                @if (preview.overlappingActions.length) { <p>{{ preview.overlappingActions.length }} overlapping active action(s) remain recorded.</p> }
                @for (warning of preview.warnings; track warning) { <div class="warning">{{ warning }}</div> }
                <button type="button" [disabled]="saving() || !preview.targetVersionCurrent" (click)="confirmCreate()">{{ saving() ? 'Applying…' : 'Confirm enforcement' }}</button>
              </section>
            }
          </article>
        }

        @if (revokeTarget(); as target) {
          <article class="panel action-workbench revoke"><header><div><p>Reinstatement</p><h3>Revoke {{ target.actionType }} action</h3></div><button class="text" (click)="cancelRevoke()">Close</button></header>
            <p>Original scopes: <strong>{{ scopeList(target.scopes) }}</strong>. Created {{ formatDate(target.createdAt) }}.</p>
            <p>Original reason: <strong>{{ target.reasonCode }}</strong> — {{ target.reason }}</p>
            <label>Reinstatement reason<textarea [(ngModel)]="revokeReason" (ngModelChange)="revokePreview.set(null)" rows="3"></textarea></label>
            <div class="button-row"><button [disabled]="previewing() || !revokeReason.trim()" (click)="previewRevocation()">Preview reinstatement</button></div>
            @if (revokePreview(); as preview) { <section class="preview"><h4>Revocation preview</h4><p>Restrictions after revocation: {{ effectiveList(preview) }}</p>
              @if (preview.effectiveRestrictionsAfter.length) { <div class="warning">Other restrictions remain; this is not a full access restoration.</div> }
              <button [disabled]="saving()" (click)="confirmRevoke()">Confirm reinstatement</button></section> }
          </article>
        }

        @if (admin.hasPermission(permissions.AUDIT_READ)) {
          <article class="panel timeline"><header><h3>Audit timeline</h3><span>{{ timeline().length }}</span></header>
            @if (!timeline().length) { <p class="muted">No user enforcement history.</p> }
            @for (entry of timeline(); track entry.eventId) { <div class="timeline-row"><span></span><div><strong>{{ eventLabel(entry) }}</strong><p>{{ entry.actionType }} · {{ scopeList(entry.scopes) }}</p><small>{{ entry.actorDisplayName }} · {{ formatDate(entry.occurredAt) }} · {{ entry.reasonCode }}</small></div></div> }
          </article>
        }
      }
    </section>
  `,
  styles: [`
    .user-detail{display:grid;gap:1rem}.back{color:var(--color-text-muted);font-size:.82rem;text-decoration:none}.hero{display:flex;align-items:end;justify-content:space-between;padding:1.2rem;border:1px solid var(--color-border);border-radius:var(--radius-md);background:linear-gradient(120deg,rgba(56,189,248,.1),rgba(18,19,23,.9) 48%)}.hero p,.action-workbench header p,.ledger header p{margin:0 0 .25rem;color:var(--color-info);font-size:.72rem;font-weight:850;text-transform:uppercase;letter-spacing:.08em}.hero h2{margin:0;font-size:1.8rem}.hero span{color:var(--color-text-muted);font-size:.75rem}.hero-state{text-align:right}.hero-state small{display:block;color:var(--color-text-muted)}.hero-state strong{font-size:1.1rem;color:#fdba74}
    .panel,.notice{border:1px solid var(--color-border);border-radius:var(--radius-md);background:rgba(18,19,23,.9);padding:1rem}.panel h3{margin:0;font-size:1rem}.panel>header{display:flex;align-items:center;justify-content:space-between;gap:1rem;margin-bottom:.8rem}.panel>header>span{color:var(--color-text-muted);font-size:.75rem}.notice{border-color:rgba(251,191,36,.35);color:#fde68a}.error,.inline-error{color:var(--color-danger)}.summary-grid,.actions-layout{display:grid;grid-template-columns:1fr 1fr;gap:1rem}dl{display:grid;gap:.55rem;margin:.8rem 0 0}dl>div{display:flex;justify-content:space-between;gap:1rem;border-bottom:1px solid var(--color-border);padding-bottom:.45rem}dt{color:var(--color-text-muted);font-size:.78rem}dd{margin:0;text-align:right;color:var(--color-text-secondary);font-size:.8rem}em{color:var(--color-text-muted);font-size:.7rem}.membership{display:grid;gap:.2rem;padding:.6rem 0;border-top:1px solid var(--color-border)}.membership span,.muted{color:var(--color-text-muted);font-size:.78rem}
    .scope-grid{display:grid;grid-template-columns:repeat(4,1fr);gap:.65rem}.scope-row{display:grid;gap:.4rem;padding:.75rem;border:1px solid var(--color-border);border-radius:var(--radius-md);background:var(--color-bg-primary);font-size:.78rem}.scope-row span{color:var(--color-text-muted)}.allowed{color:#86efac}.blocked{color:#fca5a5}.reserved strong{color:var(--color-text-muted)}.action-card,.history-row{display:flex;align-items:center;justify-content:space-between;gap:1rem;padding:.7rem 0;border-top:1px solid var(--color-border)}.action-card>div{display:grid;gap:.2rem}.action-card span,.history-row span{color:var(--color-text-secondary);font-size:.78rem}.action-card small{color:var(--color-text-muted);font-size:.7rem}
    .form-grid{display:grid;grid-template-columns:1fr 1fr;gap:.8rem}.form-grid label,.action-workbench>label{display:grid;gap:.35rem;color:var(--color-text-muted);font-size:.76rem;font-weight:750}.form-grid .wide{grid-column:1/-1}input,select,textarea{border:1px solid var(--color-border);border-radius:var(--radius-md);background:var(--color-bg-primary);color:var(--color-text-primary);padding:.6rem;font:inherit}fieldset{display:flex;align-items:center;gap:1rem;border:1px solid var(--color-border);border-radius:var(--radius-md)}fieldset label,.confirm{display:flex!important;align-items:center;gap:.45rem}.button-row{display:flex;justify-content:flex-end;margin-top:.8rem}button{min-height:40px;border:1px solid var(--color-info);border-radius:var(--radius-md);background:rgba(56,189,248,.13);color:var(--color-info);padding:.5rem .8rem;font:inherit;font-weight:800;cursor:pointer}button.secondary{border-color:var(--color-border);color:var(--color-text-secondary)}button.text{border:0;background:transparent;color:var(--color-text-muted)}button:disabled{opacity:.45}.preview{margin-top:1rem;border-left:3px solid var(--color-info);background:rgba(56,189,248,.06);padding:1rem}.preview h4{margin:0 0 .6rem}.preview p{color:var(--color-text-secondary);font-size:.82rem}.warning{margin:.5rem 0;color:#fde68a;font-size:.8rem}.timeline-row{display:grid;grid-template-columns:12px 1fr;gap:.6rem;padding:.7rem 0}.timeline-row>span{width:9px;height:9px;margin-top:.3rem;border-radius:50%;background:var(--color-info)}.timeline-row p{margin:.2rem 0;color:var(--color-text-secondary);font-size:.78rem}.timeline-row small{color:var(--color-text-muted);font-size:.7rem}
    @media(max-width:800px){.summary-grid,.actions-layout,.scope-grid{grid-template-columns:1fr 1fr}.form-grid{grid-template-columns:1fr}}@media(max-width:560px){.hero{align-items:start;flex-direction:column;gap:1rem}.hero-state{text-align:left}.summary-grid,.actions-layout,.scope-grid{grid-template-columns:1fr}}
  `],
})
export class AdminUserDetailComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly service = inject(AdminUserService);
  readonly admin = inject(AdminService);
  readonly permissions = ADMIN_PERMISSIONS;
  readonly user = signal<AdminUserDetail | null>(null);
  readonly timeline = signal<AdminUserTimelineEntry[]>([]);
  readonly loading = signal(true);
  readonly error = signal('');
  readonly actionError = signal('');
  readonly previewing = signal(false);
  readonly saving = signal(false);
  readonly createPreview = signal<EnforcementPreview | null>(null);
  readonly revokePreview = signal<EnforcementPreview | null>(null);
  readonly revokeTarget = signal<UserEnforcementAction | null>(null);
  readonly capabilities = computed(() => userAdminCapabilities(this.user()?.availableAdminCapabilities ?? {
    canReadUser: false, canViewPii: false, canRestrict: false, canSuspend: false, canBan: false,
    canReinstate: false, self: false, protectedAccount: true, readOnlyReason: null, operationalScopes: [],
  }));
  readonly operationalScopes = computed(() => this.capabilities().availableOperationalScopes);
  actionType: UserEnforcementActionType = 'RESTRICT';
  buyingSelected = true;
  sellingSelected = false;
  reasonCode = '';
  reason = '';
  expiresAt = '';
  indefiniteConfirmed = false;
  revokeReason = '';
  private idempotencyKey: string | null = null;
  private revokeIdempotencyKey: string | null = null;
  private readonly userId = this.route.snapshot.paramMap.get('userId') ?? '';

  ngOnInit(): void { this.load(); }
  load(): void {
    this.loading.set(true); this.error.set('');
    this.service.detail(this.userId).subscribe({
      next: response => { this.user.set(response.data); this.loading.set(false); this.loadTimeline(); },
      error: response => { this.error.set(response.status === 403 ? 'You do not have permission to read this user.' : 'User detail could not be loaded.'); this.loading.set(false); },
    });
  }
  private loadTimeline(): void {
    if (!this.admin.hasPermission(this.permissions.AUDIT_READ)) return;
    this.service.timeline(this.userId).subscribe({ next: response => this.timeline.set(response.data), error: () => this.timeline.set([]) });
  }
  canCreate(): boolean { return canCreateUserEnforcement(this.capabilities(), this.actionType); }
  canPreview(): boolean {
    const scopes = this.selectedScopes();
    return this.canCreate() && scopes.length > 0 && Boolean(this.reasonCode.trim()) && Boolean(this.reason.trim())
      && !(this.actionType === 'SUSPEND' && !this.expiresAt && !this.indefiniteConfirmed);
  }
  actionTypeChanged(): void {
    if (this.actionType === 'BAN') {
      const scopes = this.operationalScopes();
      this.buyingSelected = scopes.includes('USER_BUYING');
      this.sellingSelected = scopes.includes('USER_SELLING');
    }
    this.invalidatePreview();
  }
  invalidatePreview(): void { this.createPreview.set(null); this.idempotencyKey = null; this.actionError.set(''); }
  previewCreate(): void {
    const request = this.createRequest(null); if (!request) return;
    this.previewing.set(true); this.actionError.set('');
    this.service.previewCreate(this.userId, request).subscribe({
      next: response => { this.createPreview.set(response.data); this.idempotencyKey = this.newIdempotencyKey(); this.previewing.set(false); },
      error: response => { this.actionError.set(this.message(response, 'The action could not be previewed.')); this.previewing.set(false); },
    });
  }
  confirmCreate(): void {
    const request = this.createRequest(this.idempotencyKey); if (!request || !this.createPreview()) return;
    this.saving.set(true);
    this.service.create(this.userId, request).subscribe({ next: () => this.afterMutation(), error: response => this.mutationFailed(response) });
  }
  beginRevoke(action: UserEnforcementAction): void { this.revokeTarget.set(action); this.revokeReason = ''; this.revokePreview.set(null); this.revokeIdempotencyKey = null; }
  cancelRevoke(): void { this.revokeTarget.set(null); this.revokePreview.set(null); }
  previewRevocation(): void {
    const target = this.revokeTarget(); if (!target?.enforcementActionId) return;
    this.previewing.set(true); this.actionError.set('');
    this.service.previewRevoke(this.userId, target.enforcementActionId, this.revokeRequest(target, null)).subscribe({
      next: response => { this.revokePreview.set(response.data); this.revokeIdempotencyKey = this.newIdempotencyKey(); this.previewing.set(false); },
      error: response => { this.actionError.set(this.message(response, 'Reinstatement could not be previewed.')); this.previewing.set(false); },
    });
  }
  confirmRevoke(): void {
    const target = this.revokeTarget(); if (!target?.enforcementActionId || !this.revokePreview()) return;
    this.saving.set(true);
    this.service.revoke(this.userId, target.enforcementActionId, this.revokeRequest(target, this.revokeIdempotencyKey)).subscribe({
      next: () => this.afterMutation(), error: response => this.mutationFailed(response),
    });
  }
  private afterMutation(): void { this.saving.set(false); this.createPreview.set(null); this.cancelRevoke(); this.load(); }
  private mutationFailed(response: HttpErrorResponse): void {
    this.saving.set(false); this.actionError.set(this.message(response, 'The enforcement action could not be completed.'));
    if (response.status === 409) { this.createPreview.set(null); this.revokePreview.set(null); this.load(); }
  }
  private createRequest(idempotencyKey: string | null): CreateUserEnforcementRequest | null {
    const current = this.user(); if (!current) return null;
    let expiresAt: string | null = null;
    if (this.expiresAt) { const parsed = new Date(this.expiresAt); if (Number.isNaN(parsed.getTime())) { this.actionError.set('Expiration is invalid.'); return null; } expiresAt = parsed.toISOString(); }
    return { actionType: this.actionType, scopes: this.selectedScopes(), reasonCode: this.reasonCode.trim().toUpperCase(),
      reason: this.reason.trim(), effectiveAt: null, expiresAt, expectedUserVersion: current.version, idempotencyKey, safeMetadata: { origin: 'admin-user-detail' } };
  }
  private revokeRequest(action: UserEnforcementAction, idempotencyKey: string | null): RevokeUserEnforcementRequest {
    return { expectedEnforcementVersion: action.version, reasonCode: 'ADMIN_REINSTATEMENT', reason: this.revokeReason.trim(), idempotencyKey, safeMetadata: { origin: 'admin-user-detail' } };
  }
  private selectedScopes(): UserEnforcementScope[] {
    const available = this.operationalScopes();
    return this.actionType === 'BAN' ? [...available] : [
      ...(this.buyingSelected && available.includes('USER_BUYING') ? ['USER_BUYING' as const] : []),
      ...(this.sellingSelected && available.includes('USER_SELLING') ? ['USER_SELLING' as const] : []),
    ];
  }
  restrictionFor(scope: UserEnforcementScope) { return this.user()?.effectiveRestrictions.find(item => item.scope === scope); }
  scopeLabel(scope: UserEnforcementScope): string { return scope === 'USER_BUYING' ? 'Buying' : 'Selling'; }
  scopeList(scopes: UserEnforcementScope[]): string { return scopes.map(scope => this.scopeLabel(scope)).join(', ') || 'none'; }
  effectiveList(preview: EnforcementPreview): string { return preview.effectiveRestrictionsAfter.length ? preview.effectiveRestrictionsAfter.map(item => `${this.scopeLabel(item.scope)} (${item.actionType})`).join(', ') : 'none'; }
  eventLabel(entry: AdminUserTimelineEntry): string { return entry.eventType === 'REVOKED' ? 'Enforcement revoked' : 'Enforcement created'; }
  formatDate(value: string): string { return new Date(value).toLocaleString(); }
  private message(response: HttpErrorResponse, fallback: string): string { return response.error?.error?.message || fallback; }
  private newIdempotencyKey(): string {
    return globalThis.crypto?.randomUUID?.() ?? `admin-user-${Date.now()}-${Math.random().toString(36).slice(2)}`;
  }
}
