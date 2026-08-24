import { DatePipe } from '@angular/common';
import { Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { InvestigationAssignment, InvestigationCasePage, InvestigationStatus } from '../../core/models/investigation-case.model';
import { ReportSeverity, ReportTargetType } from '../../core/models/report.model';
import { InvestigationCaseService } from '../../core/services/investigation-case.service';

@Component({
  selector: 'app-admin-investigation-case-inbox', standalone: true,
  imports: [DatePipe, FormsModule, RouterLink],
  template: `
    <section class="page">
      <header><div><p class="eyebrow">Trust & Safety workspace</p><h1>Investigation cases</h1><p>Human investigation between report triage and any future enforcement decision.</p></div><span class="count">{{ page()?.totalElements || 0 }} cases</span></header>
      <nav class="tabs" aria-label="Case inbox views">
        @for(tab of tabs;track tab.label){<button type="button" [attr.aria-pressed]="activeTab===tab.label" [class.active]="activeTab===tab.label" (click)="selectTab(tab.label,tab.status,tab.assignment)">{{ tab.label }}</button>}
      </nav>
      <form class="filters" (ngSubmit)="search()">
        <label><span>Search</span><input name="query" [(ngModel)]="query" placeholder="Case ID, title, or target ID"></label>
        <label><span>Severity</span><select name="severity" [(ngModel)]="severity"><option value="">All severities</option>@for(value of severities;track value){<option [value]="value">{{ value }}</option>}</select></label>
        <label><span>Target</span><select name="targetType" [(ngModel)]="targetType"><option value="">All target types</option>@for(value of targetTypes;track value){<option [value]="value">{{ label(value) }}</option>}</select></label>
        <label><span>Updated from</span><input type="date" name="updatedFrom" [(ngModel)]="updatedFrom"></label>
        <button type="submit">Apply filters</button>
      </form>
      @if(loading()){<div class="state">Loading investigation cases...</div>}
      @else if(error()){<div class="state error" role="alert">{{ error() }} <button type="button" (click)="load()">Retry</button></div>}
      @else if(!page()?.items?.length){<div class="state"><strong>No cases match this view.</strong><p>Cases appear after a ready report is promoted into investigation.</p></div>}
      @else{
        <div class="table-wrap"><table><thead><tr><th>Case</th><th>Primary target</th><th>Severity</th><th>Status</th><th>Reports</th><th>Assignment</th><th>Updated</th></tr></thead><tbody>
          @for(item of page()?.items;track item.caseId){<tr><td><a [routerLink]="['/admin/cases',item.caseId]"><strong>{{ item.title }}</strong><code>{{ item.caseId }}</code></a></td><td><span class="type">{{ label(item.primaryTargetType) }}</span><strong>{{ item.safePrimaryTargetLabel }}</strong><code>{{ item.primaryTargetId }}</code></td><td><span class="severity" [attr.data-value]="item.severity">{{ item.severity }}</span></td><td>{{ label(item.status) }}</td><td>{{ item.linkedReportCount }}</td><td>{{ item.assignedAdminId || 'Unassigned' }}</td><td>{{ item.updatedAt|date:'medium' }}</td></tr>}
        </tbody></table></div>
        <footer><button type="button" [disabled]="(page()?.page||0)===0" (click)="move(-1)">Previous</button><span>Page {{ (page()?.page||0)+1 }} of {{ page()?.totalPages||1 }}</span><button type="button" [disabled]="(page()?.page||0)+1 >= (page()?.totalPages||1)" (click)="move(1)">Next</button></footer>
      }
    </section>
  `,
  styles: [`
    .page{display:grid;gap:1rem}header{display:flex;justify-content:space-between;align-items:end;gap:1rem}h1,p{margin:0}h1{font-family:var(--font-display);font-size:2rem;color:var(--color-text-primary)}header p:last-child{margin-top:.35rem;color:var(--color-text-secondary)}.eyebrow{color:var(--color-info)!important;font-size:.72rem;font-weight:900;letter-spacing:.09em;text-transform:uppercase}.count,.type,.severity{display:inline-flex;border-radius:999px;padding:.3rem .55rem;background:rgba(56,189,248,.1);color:var(--color-info);font-size:.72rem;font-weight:900}.tabs{display:flex;gap:.4rem;overflow:auto}.tabs button{white-space:nowrap;border:1px solid var(--color-border);border-radius:999px;background:var(--color-bg-secondary);color:var(--color-text-secondary);padding:.55rem .85rem;font:inherit;font-weight:800;cursor:pointer}.tabs button.active{border-color:var(--color-info);color:var(--color-info);background:rgba(56,189,248,.1)}.filters{display:grid;grid-template-columns:minmax(220px,2fr) repeat(3,1fr) auto;gap:.65rem;padding:1rem;border:1px solid var(--color-border);border-radius:var(--radius-lg);background:var(--color-bg-secondary)}label{display:grid;gap:.3rem;color:var(--color-text-muted);font-size:.72rem;font-weight:850}input,select,.filters button,footer button,.state button{min-height:40px;border:1px solid var(--color-border);border-radius:var(--radius-md);padding:.55rem .7rem;background:var(--color-bg-primary);color:var(--color-text-primary);font:inherit}.filters button{align-self:end;border:0;background:var(--color-accent);color:#07111f;font-weight:900;cursor:pointer}.table-wrap{overflow:auto;border:1px solid var(--color-border);border-radius:var(--radius-lg);background:var(--color-bg-secondary)}table{width:100%;border-collapse:collapse;min-width:960px}th,td{padding:.85rem;text-align:left;border-bottom:1px solid var(--color-border);vertical-align:top}th{color:var(--color-text-muted);font-size:.7rem;text-transform:uppercase}td{color:var(--color-text-secondary);font-size:.85rem}td a,td>strong,td code{display:block}td a{color:var(--color-text-primary);text-decoration:none}td code{margin-top:.3rem;color:var(--color-text-muted);font-size:.7rem}.type{margin-bottom:.35rem}.severity[data-value=CRITICAL]{background:rgba(239,68,68,.14);color:var(--color-danger)}.severity[data-value=HIGH]{background:rgba(245,158,11,.14);color:var(--color-warning)}.state{padding:2rem;border:1px dashed var(--color-border);border-radius:var(--radius-lg);text-align:center;color:var(--color-text-secondary)}.state p{margin-top:.35rem}.state.error{color:var(--color-danger)}footer{display:flex;align-items:center;justify-content:flex-end;gap:.75rem;color:var(--color-text-muted)}footer button{cursor:pointer}footer button:disabled{opacity:.45;cursor:not-allowed}@media(max-width:850px){header{align-items:flex-start;flex-direction:column}.filters{grid-template-columns:1fr 1fr}}@media(max-width:560px){.filters{grid-template-columns:1fr}}
  `],
})
export class AdminInvestigationCaseInboxComponent implements OnInit {
  private readonly cases = inject(InvestigationCaseService);
  private readonly route = inject(ActivatedRoute);
  private readonly destroy = inject(DestroyRef);
  readonly page = signal<InvestigationCasePage | null>(null); readonly loading = signal(true); readonly error = signal('');
  readonly severities: ReportSeverity[] = ['LOW','MEDIUM','HIGH','CRITICAL'];
  readonly targetTypes: ReportTargetType[] = ['USER','BUSINESS','LISTING'];
  readonly routeStatuses: InvestigationStatus[] = ['OPEN','UNDER_INVESTIGATION','READY_FOR_ACTION','CLOSED_NO_ACTION','CLOSED_ACTIONED'];
  readonly tabs: {label:string;status:InvestigationStatus|'';assignment:InvestigationAssignment}[] = [
    {label:'Open',status:'OPEN',assignment:'ALL'}, {label:'In progress',status:'UNDER_INVESTIGATION',assignment:'ALL'},
    {label:'Assigned to me',status:'',assignment:'ASSIGNED_TO_ME'},
    {label:'Unassigned',status:'',assignment:'UNASSIGNED'}, {label:'Ready for action',status:'READY_FOR_ACTION',assignment:'ALL'},
    {label:'Closed',status:'CLOSED_NO_ACTION',assignment:'ALL'},
  ];
  activeTab='Open'; status:InvestigationStatus|''='OPEN'; assignment:InvestigationAssignment='ALL';
  query='';severity:ReportSeverity|''='';targetType:ReportTargetType|''='';updatedFrom='';private pageIndex=0;
  ngOnInit(){this.route.queryParamMap.pipe(takeUntilDestroyed(this.destroy)).subscribe(params=>{
    const status=params.get('status');const assignment=params.get('assignment');
    this.status=this.routeStatuses.includes(status as InvestigationStatus)?status as InvestigationStatus:'OPEN';
    this.assignment=this.tabs.some(tab=>tab.assignment===assignment)?assignment as InvestigationAssignment:'ALL';
    this.activeTab=this.tabs.find(tab=>tab.status===this.status&&tab.assignment===this.assignment)?.label??'';
    this.pageIndex=0;this.load();
  })}
  selectTab(label:string,status:InvestigationStatus|'',assignment:InvestigationAssignment){this.activeTab=label;this.status=status;this.assignment=assignment;this.pageIndex=0;this.load()}
  search(){this.pageIndex=0;this.load()} move(delta:number){this.pageIndex+=delta;this.load()}
  load(){this.loading.set(true);this.error.set('');this.cases.search({q:this.query.trim()||undefined,status:this.status,severity:this.severity,targetType:this.targetType,assignment:this.assignment,updatedFrom:this.updatedFrom?new Date(this.updatedFrom).toISOString():undefined,page:this.pageIndex,size:25,sort:'updatedAt,desc'}).subscribe({next:value=>{this.page.set(value);this.loading.set(false)},error:error=>{this.loading.set(false);this.error.set(error.status===403?'You do not have permission to read investigation cases.':'Investigation cases could not be loaded.')}})}
  label(value:string){return value.toLowerCase().replaceAll('_',' ').replace(/^./,c=>c.toUpperCase())}
}
