import { DatePipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { AppealReason, EnforcementNotice, MyAppeal } from '../../core/models/appeal.model';
import { AppealService } from '../../core/services/appeal.service';

@Component({
  selector: 'app-account-appeals', standalone: true, imports: [DatePipe, FormsModule, RouterLink],
  template: `
    <main class="appeals-page">
      <a routerLink="/account" class="back">← Account</a>
      <header>
        <p class="eyebrow">Decision review</p>
        <h1>Appeals</h1>
        <p>Challenge a specific marketplace restriction. Each appeal is reviewed by platform staff.</p>
      </header>
      @if(loading()){<section class="state">Loading enforcement notices and appeals…</section>}
      @else if(error()){<section class="state error" role="alert">{{error()}} <button type="button" (click)="load()">Retry</button></section>}
      @else{
        <section class="ledger" aria-labelledby="notices-heading">
          <div class="section-heading"><div><p class="eyebrow">Current restrictions</p><h2 id="notices-heading">Enforcement notices</h2></div><span>{{notices().length}}</span></div>
          @if(!notices().length){<p class="empty">You have no active marketplace restrictions eligible for review.</p>}
          @for(item of notices();track item.enforcementActionId){
            <article class="notice">
              <div class="notice-mark" [attr.data-action]="item.actionType">{{item.actionType.charAt(0)}}</div>
              <div><div class="title-line"><strong>{{label(item.actionType)}}</strong><span>{{label(item.targetType)}}</span></div><h3>{{item.safeTargetLabel}}</h3>
                <p>{{scopeLabel(item.scopes)}}</p><small>Effective {{item.effectiveAt|date:'medium'}} · {{item.expiresAt ? ('Expires '+(item.expiresAt|date:'medium')) : 'No scheduled expiry'}} · {{item.supportReference}}</small>
              </div>
              <div class="notice-action">
                @if(item.appealEligible){<button type="button" (click)="select(item)">Appeal this action</button>}
                @else if(item.appealId){<span class="status">{{label(item.appealStatus||'SUBMITTED')}}</span>}
                @else{<span class="muted">{{item.appealIneligibilityReason}}</span>}
              </div>
            </article>
          }
        </section>
        @if(selected()){
          <section class="form-card" aria-labelledby="appeal-form-heading">
            <div><p class="eyebrow">{{selected()?.supportReference}}</p><h2 id="appeal-form-heading">Request a review</h2><p>Your appeal applies only to this enforcement action. Submitting it does not pause the current restriction.</p></div>
            <form (ngSubmit)="submit()">
              <label><span>Reason</span><select name="reason" [(ngModel)]="reason" required>@for(value of reasons;track value){<option [value]="value">{{label(value)}}</option>}</select></label>
              <label><span>Explanation</span><textarea name="explanation" [(ngModel)]="explanation" maxlength="2000" [required]="reason==='OTHER'" placeholder="Explain what staff should reconsider. Include any relevant reference details."></textarea><small>{{explanation.length}} / 2,000</small></label>
              @if(formError()){<p class="form-error" role="alert">{{formError()}}</p>}
              <div class="actions"><button type="button" class="quiet" (click)="cancel()">Cancel</button><button type="submit" [disabled]="submitting()">{{submitting()?'Submitting…':'Submit appeal'}}</button></div>
            </form>
          </section>
        }
        <section class="history" aria-labelledby="history-heading">
          <div class="section-heading"><div><p class="eyebrow">Your records</p><h2 id="history-heading">Submitted appeals</h2></div><span>{{appeals().length}}</span></div>
          @if(!appeals().length){<p class="empty">No appeals submitted yet.</p>}
          @for(item of appeals();track item.appealId){<article><div><strong>{{item.safeTargetLabel}}</strong><p>{{label(item.actionType)}} · {{item.supportReference}}</p>@if(item.safeOutcomeSummary){<p class="outcome">{{item.safeOutcomeSummary}}</p>}@if(item.currentEffectiveEnforcementState){<small>Current effective enforcement: {{label(item.currentEffectiveEnforcementState)}}</small>}</div><span class="status">{{label(item.status)}}</span><time>@if(item.resolvedAt){Decision {{item.resolvedAt|date:'medium'}}}@else{Updated {{item.updatedAt|date:'medium'}}}</time></article>}
        </section>
      }
    </main>
  `,
  styles:[`
    :host{display:block;min-height:100vh;background:linear-gradient(145deg,#fff9fd,#f7f0ff);color:#38244f}.appeals-page{width:min(100% - 32px,980px);margin:auto;padding:2rem 0 4rem;display:grid;gap:1.25rem}.back{color:#8b5cf6;text-decoration:none;font-weight:900}header,.ledger,.history,.form-card{background:rgba(255,255,255,.84);border:1px solid rgba(139,92,246,.2);border-radius:20px;padding:1.25rem;box-shadow:0 14px 38px rgba(88,50,122,.08)}h1,h2,h3,p{margin:0}h1{font-size:2.2rem}header>p:last-child,.form-card>div>p:last-child{margin-top:.45rem;color:#7f6b94}.eyebrow{color:#a43a86;font-size:.72rem;font-weight:950;letter-spacing:.1em;text-transform:uppercase}.section-heading{display:flex;justify-content:space-between;align-items:center}.section-heading span{min-width:2rem;height:2rem;display:grid;place-items:center;border-radius:50%;background:#f3e8ff;color:#8b5cf6;font-weight:950}.notice{display:grid;grid-template-columns:44px minmax(0,1fr) auto;gap:1rem;align-items:center;padding:1rem 0;border-top:1px solid rgba(139,92,246,.14)}.notice:first-of-type{margin-top:1rem}.notice-mark{width:44px;height:44px;display:grid;place-items:center;border-radius:12px;background:#fff0f5;color:#be356f;font-weight:950}.title-line{display:flex;gap:.5rem;align-items:center}.title-line span,.status{border-radius:999px;padding:.28rem .55rem;background:#f3e8ff;color:#7043c1;font-size:.72rem;font-weight:900}.notice h3{margin:.2rem 0}.notice p,.notice small,.history p,.history small,.muted{color:#7f6b94}.notice small,.history small{display:block;margin-top:.35rem}.history .outcome{margin-top:.45rem;color:#4d2d69;font-weight:750}.notice button,.actions button,.state button{border:0;border-radius:12px;padding:.7rem .9rem;background:#8b5cf6;color:white;font:inherit;font-weight:900;cursor:pointer}.form-card{border-left:5px solid #8b5cf6;display:grid;grid-template-columns:minmax(220px,.8fr) 1.2fr;gap:1.5rem}.form-card form,label{display:grid;gap:.4rem}.form-card form{gap:1rem}label span{font-size:.76rem;font-weight:900;color:#6f5787}select,textarea{border:1px solid rgba(139,92,246,.25);border-radius:12px;background:white;color:#38244f;padding:.75rem;font:inherit}textarea{min-height:130px;resize:vertical}label small{text-align:right;color:#8e7a9f}.actions{display:flex;justify-content:flex-end;gap:.6rem}.actions .quiet{background:#f1e9f7;color:#6f5787}.form-error,.state.error{color:#b4235d}.history article{display:grid;grid-template-columns:1fr auto auto;gap:1rem;align-items:center;padding:1rem 0;border-top:1px solid rgba(139,92,246,.14)}time{color:#8e7a9f;font-size:.8rem}.empty,.state{padding:1.3rem;text-align:center;color:#7f6b94}.state{background:white;border-radius:16px}@media(max-width:680px){.form-card{grid-template-columns:1fr}.notice{grid-template-columns:44px 1fr}.notice-action{grid-column:2}.history article{grid-template-columns:1fr auto}.history time{grid-column:1/3}}
  `]
})
export class AccountAppealsComponent implements OnInit {
  private readonly service=inject(AppealService);
  readonly loading=signal(true);readonly error=signal('');readonly notices=signal<EnforcementNotice[]>([]);readonly appeals=signal<MyAppeal[]>([]);
  readonly selected=signal<EnforcementNotice|null>(null);readonly submitting=signal(false);readonly formError=signal('');
  readonly reasons:AppealReason[]=['DECISION_INCORRECT','NEW_EVIDENCE','ACCOUNT_COMPROMISED','MISIDENTIFICATION','ACTION_TOO_SEVERE','POLICY_MISAPPLIED','OTHER'];
  reason:AppealReason='DECISION_INCORRECT';explanation='';
  ngOnInit(){this.load()} load(){this.loading.set(true);this.error.set('');forkJoin({notices:this.service.notices(),appeals:this.service.mine()}).subscribe({next:v=>{this.notices.set(v.notices);this.appeals.set(v.appeals);this.loading.set(false)},error:()=>{this.error.set('Appeal information could not be loaded.');this.loading.set(false)}})}
  select(item:EnforcementNotice){this.selected.set(item);this.reason='DECISION_INCORRECT';this.explanation='';this.formError.set('')}
  cancel(){this.selected.set(null);this.formError.set('')}
  submit(){const item=this.selected();if(!item)return;if(this.reason==='OTHER'&&!this.explanation.trim()){this.formError.set('Add an explanation when choosing Other.');return}this.submitting.set(true);this.formError.set('');this.service.submit(item.enforcementActionId,this.reason,this.explanation).subscribe({next:()=>{this.submitting.set(false);this.selected.set(null);this.load()},error:error=>{this.submitting.set(false);this.formError.set(error.status===409?'An appeal already exists for this action.':'The appeal could not be submitted. Review the form and try again.')}})}
  label(value:string){return value.toLowerCase().replaceAll('_',' ').replace(/^./,c=>c.toUpperCase())}
  scopeLabel(values:string[]){return values.map(v=>this.label(v)).join(' · ')}
}
