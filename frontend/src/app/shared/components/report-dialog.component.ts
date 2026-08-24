import { Component, EventEmitter, Input, Output, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ReportReason, ReportTargetType, REPORT_REASONS } from '../../core/models/report.model';
import { ReportService } from '../../core/services/report.service';

@Component({
  selector: 'app-report-dialog', standalone: true, imports: [FormsModule],
  template: `
    <div class="backdrop" (click)="close.emit()">
      <section class="dialog" role="dialog" aria-modal="true" [attr.aria-labelledby]="'report-title'" (click)="$event.stopPropagation()">
        <header><div><p>Marketplace safety</p><h2 id="report-title">Report {{ targetLabel }}</h2></div><button type="button" aria-label="Close report dialog" (click)="close.emit()">×</button></header>
        @if (submitted()) {
          <div class="confirmation" role="status"><strong>Your report has been submitted for review.</strong><p>Reference {{ supportReference() }}</p><button type="button" (click)="close.emit()">Done</button></div>
        } @else {
          <form (ngSubmit)="submit()">
            <label><span>What best describes the concern?</span><select name="reason" [(ngModel)]="reason" required>
              <option value="" disabled>Select a reason</option>@for (option of reasons; track option.value) { <option [value]="option.value">{{ option.label }}</option> }
            </select></label>
            <label><span>Explanation @if (reason === 'OTHER') { (required) }</span><textarea name="description" [(ngModel)]="description" maxlength="2000" rows="5" [required]="reason === 'OTHER'" placeholder="Share factual context that may help review."></textarea><small>{{ description.length }}/2000</small></label>
            <p class="neutral">Reports are reviewed as allegations. Submitting does not automatically remove content or restrict an account.</p>
            @if (errorMsg()) { <p class="error" role="alert">{{ errorMsg() }}</p> }
            <footer><button type="button" class="secondary" (click)="close.emit()">Cancel</button><button type="submit" [disabled]="loading() || !valid()">{{ loading() ? 'Submitting...' : 'Submit report' }}</button></footer>
          </form>
        }
      </section>
    </div>
  `,
  styles: [`
    .backdrop{position:fixed;inset:0;z-index:1000;display:grid;place-items:center;padding:1rem;background:rgba(24,16,24,.64)}
    .dialog{width:min(100%,560px);border:1px solid var(--market-line,#ddd);border-radius:14px;background:#fff;color:var(--market-ink,#241826);box-shadow:0 24px 80px rgba(20,10,22,.28);overflow:hidden}
    header,footer{display:flex;align-items:center;justify-content:space-between;gap:1rem;padding:1rem 1.25rem;border-bottom:1px solid var(--market-line,#ddd)} footer{border:0;border-top:1px solid var(--market-line,#ddd);padding:1rem 0 0}
    header p,header h2,.neutral,.error,.confirmation p{margin:0} header p{color:var(--market-accent-dark,#7a315f);font-size:.75rem;font-weight:900;text-transform:uppercase} header h2{font-size:1.35rem} header>button{border:0;background:transparent;font-size:1.8rem;cursor:pointer}
    form,.confirmation{display:grid;gap:1rem;padding:1.25rem} label{display:grid;gap:.4rem;font-weight:850} select,textarea{width:100%;box-sizing:border-box;border:1px solid var(--market-line,#ccc);border-radius:8px;padding:.75rem;background:#fff;color:inherit;font:inherit} textarea{resize:vertical} small{justify-self:end;color:var(--market-muted,#666)}
    .neutral{border-left:3px solid var(--market-accent,#b84a8c);padding:.7rem .8rem;background:#fff4fa;color:var(--market-muted,#66505e);line-height:1.5}.error{color:#b4234f}.confirmation{text-align:center;padding:2rem 1.25rem}.confirmation button{justify-self:center}
    button{min-height:40px;border:0;border-radius:8px;padding:.65rem 1rem;background:var(--market-accent-dark,#7a315f);color:#fff;font:inherit;font-weight:900;cursor:pointer}button.secondary{border:1px solid var(--market-line,#ddd);background:#fff;color:inherit}button:disabled{opacity:.55;cursor:not-allowed}
  `],
})
export class ReportDialogComponent {
  private readonly service = inject(ReportService);
  @Input({ required: true }) targetType!: ReportTargetType;
  @Input({ required: true }) targetId = '';
  @Input() targetLabel = 'item';
  @Output() close = new EventEmitter<void>();
  readonly loading = signal(false); readonly submitted = signal(false); readonly errorMsg = signal(''); readonly supportReference = signal('');
  reason: ReportReason | '' = ''; description = '';
  get reasons() { return REPORT_REASONS[this.targetType] || []; }
  valid() { return !!this.reason && (this.reason !== 'OTHER' || !!this.description.trim()); }
  submit() {
    if (!this.valid() || this.loading()) return;
    this.loading.set(true); this.errorMsg.set('');
    this.service.submit(this.targetType, this.targetId, this.reason as ReportReason, this.description.trim() || null).subscribe({
      next: result => { this.loading.set(false); this.supportReference.set(result.supportReference); this.submitted.set(true); },
      error: error => { this.loading.set(false); this.errorMsg.set(error?.error?.error?.code === 'REPORT_ALREADY_SUBMITTED'
        ? 'You already sent this report recently.' : 'Your report could not be submitted. Please try again.'); },
    });
  }
}
