import { DatePipe } from '@angular/common';
import { Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { AdminReportPage, AdminReportSummary, ReportAssignment, ReportReason, ReportSeverity, ReportStatus, ReportTargetType } from '../../core/models/report.model';
import { ReportService } from '../../core/services/report.service';
import { ToastService } from '../../core/services/toast.service';

@Component({
  selector: 'app-admin-report-inbox', standalone: true, imports: [DatePipe, FormsModule],
  template: `
    <section class="page">
      <header><div><p class="eyebrow">Trust & Safety</p><h1>Report inbox</h1><p>Review allegations without taking automatic marketplace action.</p></div><button type="button" class="secondary" (click)="load()" [disabled]="loading()">Refresh</button></header>
      <div class="assignment-tabs" aria-label="Assignment filters">
        @for (tab of assignments; track tab.value) { <button type="button" [class.active]="assignment === tab.value" [attr.aria-pressed]="assignment === tab.value" (click)="assignment=tab.value;page=0;load()">{{ tab.label }}</button> }
      </div>
      <form class="filters" (ngSubmit)="page=0;load()">
        <label class="search"><span>Exact ID search</span><input name="q" [(ngModel)]="q" maxlength="26" placeholder="Report, target, or reporter ID"></label>
        <label><span>Status</span><select name="status" [(ngModel)]="status"><option value="">All</option>@for (value of statuses;track value){<option [value]="value">{{ label(value) }}</option>}</select></label>
        <label><span>Target</span><select name="target" [(ngModel)]="targetType"><option value="">All</option>@for (value of targets;track value){<option [value]="value">{{ value }}</option>}</select></label>
        <label><span>Reason</span><select name="reason" [(ngModel)]="reasonCode"><option value="">All</option>@for (value of reasons;track value){<option [value]="value">{{ label(value) }}</option>}</select></label>
        <label><span>Severity</span><select name="severity" [(ngModel)]="severity"><option value="">All</option>@for (value of severities;track value){<option [value]="value">{{ value }}</option>}</select></label>
        <label><span>From</span><input type="date" name="from" [(ngModel)]="createdFrom"></label>
        <button type="submit">Apply</button><button type="button" class="secondary" (click)="clear()">Clear</button>
      </form>
      @if (errorMsg()) { <div class="state error" role="alert">{{ errorMsg() }}</div> }
      @if (loading()) { <div class="state" role="status">Loading reports...</div> }
      @else if (!data()?.items?.length) { <div class="state"><strong>No reports found</strong><p>Try a different filter or return when a new report arrives.</p></div> }
      @else {
        <div class="table-wrap"><table><thead><tr><th>Report</th><th>Target</th><th>Reason</th><th>Severity</th><th>Status</th><th>Assignment</th><th>Created</th><th></th></tr></thead><tbody>
          @for (report of data()?.items || []; track report.reportId) { <tr>
            <td><code>{{ report.reportId }}</code><small>{{ report.relatedReportCount }} related</small></td>
            <td><strong>{{ report.safeTargetLabel }}</strong><small>{{ report.targetType }} · {{ report.targetId }}</small></td>
            <td>{{ label(report.reasonCode) }}</td><td><span class="chip" [attr.data-severity]="report.severity">{{ report.severity }}</span></td>
            <td><span class="chip">{{ label(report.status) }}</span></td><td>{{ report.assignedAdminId || 'Unassigned' }}</td><td>{{ report.createdAt | date:'medium' }}</td>
            <td><div class="actions">@if(report.status==='SUBMITTED'&&!report.assignedAdminId){<button type="button" (click)="claim(report)" [disabled]="actionId()===report.reportId">Claim</button>}<button type="button" class="secondary" (click)="open(report)">View</button></div></td>
          </tr> }
        </tbody></table></div>
        <footer><span>Page {{ page + 1 }} of {{ data()?.totalPages || 1 }} · {{ data()?.totalElements }} reports</span><div><button class="secondary" [disabled]="page===0" (click)="page=page-1;load()">Previous</button><button class="secondary" [disabled]="page+1 >= (data()?.totalPages || 1)" (click)="page=page+1;load()">Next</button></div></footer>
      }
    </section>
  `,
  styles: [`
    .page{display:grid;gap:1rem}header,footer,.assignment-tabs,.actions{display:flex;align-items:center;justify-content:space-between;gap:.75rem}h1,p{margin:0}h1{font:800 1.8rem var(--font-display);color:var(--color-text-primary)}header p:not(.eyebrow){margin-top:.35rem;color:var(--color-text-secondary)}.eyebrow{color:var(--color-info);font-size:.75rem;font-weight:900;text-transform:uppercase;letter-spacing:.08em}
    button{min-height:40px;border:0;border-radius:var(--radius-md);padding:.6rem .9rem;background:var(--color-accent);color:#07111f;font:inherit;font-weight:850;cursor:pointer}.secondary,.assignment-tabs button{border:1px solid var(--color-border);background:var(--color-bg-secondary);color:var(--color-text-primary)}button:disabled{opacity:.5;cursor:not-allowed}.assignment-tabs{justify-content:flex-start;flex-wrap:wrap}.assignment-tabs button.active{border-color:var(--color-info);color:var(--color-info);background:rgba(56,189,248,.1)}
    .filters{display:grid;grid-template-columns:minmax(220px,2fr) repeat(5,minmax(120px,1fr)) auto auto;gap:.65rem;align-items:end;padding:1rem;border:1px solid var(--color-border);border-radius:var(--radius-lg);background:var(--color-bg-secondary)}label{display:grid;gap:.3rem;color:var(--color-text-muted);font-size:.75rem;font-weight:850}input,select{min-height:40px;border:1px solid var(--color-border);border-radius:var(--radius-md);padding:.55rem .65rem;background:var(--color-bg-primary);color:var(--color-text-primary);font:inherit}.state{padding:1.2rem;border:1px solid var(--color-border);border-radius:var(--radius-lg);background:var(--color-bg-secondary);color:var(--color-text-secondary)}.state.error{color:var(--color-danger)}
    .table-wrap{overflow:auto;border:1px solid var(--color-border);border-radius:var(--radius-lg)}table{width:100%;border-collapse:collapse;background:var(--color-bg-secondary);font-size:.85rem}th,td{padding:.8rem;text-align:left;border-bottom:1px solid var(--color-border);vertical-align:top;color:var(--color-text-primary)}th{color:var(--color-text-muted);font-size:.72rem;text-transform:uppercase;letter-spacing:.04em}td small{display:block;margin-top:.2rem;color:var(--color-text-muted)}code{font-size:.75rem}.chip{display:inline-flex;border-radius:999px;padding:.25rem .5rem;background:var(--color-bg-primary);font-size:.72rem;font-weight:900}.chip[data-severity="HIGH"],.chip[data-severity="CRITICAL"]{color:var(--color-danger);background:rgba(244,63,94,.1)}.chip[data-severity="MEDIUM"]{color:var(--color-warning)}footer{color:var(--color-text-secondary)}footer div{display:flex;gap:.5rem}
    @media(max-width:1000px){.filters{grid-template-columns:repeat(3,1fr)}header{align-items:flex-start}}@media(max-width:650px){header,footer{align-items:stretch;flex-direction:column}.filters{grid-template-columns:1fr}.actions{flex-direction:column}.table-wrap{margin-inline:-.5rem}}
  `],
})
export class AdminReportInboxComponent implements OnInit {
  private readonly reports=inject(ReportService);private readonly router=inject(Router);private readonly route=inject(ActivatedRoute);private readonly destroy=inject(DestroyRef);private readonly toast=inject(ToastService);
  readonly loading=signal(false);readonly errorMsg=signal('');readonly data=signal<AdminReportPage|null>(null);readonly actionId=signal('');
  readonly assignments:{value:ReportAssignment;label:string}[]=[{value:'UNASSIGNED',label:'Unassigned'},{value:'ASSIGNED_TO_ME',label:'Assigned to me'},{value:'ALL',label:'All reports'}];
  readonly statuses:ReportStatus[]=['SUBMITTED','UNDER_TRIAGE','DISMISSED','READY_FOR_INVESTIGATION'];readonly targets:ReportTargetType[]=['USER','BUSINESS','LISTING'];
  readonly reasons:ReportReason[]=['SCAM','COUNTERFEIT','PROHIBITED_ITEM','MISLEADING_LISTING','HARASSMENT','SPAM','IMPERSONATION','OTHER'];readonly severities:ReportSeverity[]=['LOW','MEDIUM','HIGH','CRITICAL'];
  q='';status:ReportStatus|''='';targetType:ReportTargetType|''='';reasonCode:ReportReason|''='';severity:ReportSeverity|''='';assignment:ReportAssignment='UNASSIGNED';unresolved=false;createdFrom='';page=0;
  ngOnInit(){this.route.queryParamMap.pipe(takeUntilDestroyed(this.destroy)).subscribe(params=>{const assignment=params.get('assignment');const status=params.get('status');this.assignment=this.assignments.some(item=>item.value===assignment)?assignment as ReportAssignment:'UNASSIGNED';this.status=this.statuses.includes(status as ReportStatus)?status as ReportStatus:'';this.unresolved=params.get('unresolved')==='true';this.page=0;this.load()})} load(){this.loading.set(true);this.errorMsg.set('');this.reports.search({q:this.q.trim(),status:this.status,targetType:this.targetType,reasonCode:this.reasonCode,severity:this.severity,assignment:this.assignment,unresolved:this.unresolved||undefined,createdFrom:this.createdFrom?new Date(this.createdFrom).toISOString():undefined,page:this.page,size:25,sort:'createdAt,desc'}).subscribe({next:value=>{this.data.set(value);this.loading.set(false)},error:error=>{this.loading.set(false);this.errorMsg.set(error.status===403?'You do not have permission to read reports.':'Reports could not be loaded.')}})}
  clear(){this.q='';this.status='';this.targetType='';this.reasonCode='';this.severity='';this.createdFrom='';this.assignment='UNASSIGNED';this.unresolved=false;this.page=0;this.load()}
  claim(report:AdminReportSummary){this.actionId.set(report.reportId);this.reports.claim(report.reportId,report.version).subscribe({next:()=>{this.actionId.set('');this.toast.success('Report claimed.');this.open(report)},error:error=>{this.actionId.set('');this.errorMsg.set(error.status===409?'Report changed. The inbox has been refreshed.':'Report could not be claimed.');this.load()}})}
  open(report:AdminReportSummary){this.router.navigate(['/admin/reports',report.reportId])} label(value:string){return value.toLowerCase().replaceAll('_',' ').replace(/^./,c=>c.toUpperCase())}
}
