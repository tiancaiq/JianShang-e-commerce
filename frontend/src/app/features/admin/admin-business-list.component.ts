import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AdminBusinessSearchPage, BusinessEnforcementScope } from '../../core/models/admin-business.model';
import { AdminBusinessService } from '../../core/services/admin-business.service';

@Component({
  selector: 'app-admin-business-list', standalone: true, imports: [FormsModule, RouterLink],
  template: `
    <section class="business-list">
      <header><div><p>Marketplace operations</p><h2>Business administration</h2></div>@if(page(); as result){<span>{{result.totalElements}} businesses</span>}</header>
      <form class="filters" (ngSubmit)="applyFilters()" aria-label="Business search filters">
        <label class="search">Search<input [(ngModel)]="query" name="query" placeholder="Business ID, name, or owner user ID" /></label>
        <label>Business state<select [(ngModel)]="businessState" name="businessState"><option value="">All states</option><option>ACTIVE</option><option>SUSPENDED</option><option>CLOSED</option></select></label>
        <label>Enforcement<select [(ngModel)]="enforcementState" name="enforcementState"><option value="">All states</option><option>CLEAR</option><option>RESTRICTED</option><option>SUSPENDED</option><option>BANNED</option></select></label>
        <label>Scope<select [(ngModel)]="scope" name="scope"><option value="">All scopes</option><option value="BUSINESS_LISTING_CREATION">Listing creation</option><option value="BUSINESS_LISTING_PUBLICATION">Publication</option><option value="BUSINESS_NEW_SALES">New sales</option></select></label>
        <label>Sort<select [(ngModel)]="sort" name="sort"><option value="createdAt,desc">Newest</option><option value="createdAt,asc">Oldest</option><option value="updatedAt,desc">Recently updated</option><option value="name,asc">Name</option></select></label>
        <button type="submit">Apply</button>
      </form>
      @if(loading()){<div class="state">Loading businesses…</div>}
      @else if(error()){<div class="state error" role="alert">{{error()}} <button type="button" (click)="load()">Retry</button></div>}
      @else if(page(); as result){
        @if(!result.items.length){<div class="state">No businesses match these filters.</div>}
        @else {<div class="table-wrap"><table><thead><tr><th>Business</th><th>State</th><th>Owner</th><th>Members</th><th>Active listings</th><th>Enforcement</th><th>Scopes</th></tr></thead><tbody>
          @for(item of result.items; track item.businessId){<tr><td><a [routerLink]="['/admin/businesses',item.businessId]">{{item.displayName}}</a><small>{{item.businessId}}</small></td><td>{{item.businessState}}</td><td>{{item.ownerUserId || 'Not assigned'}}</td><td>{{item.memberCount}}</td><td>{{item.activeListingCount}}</td><td><span class="pill" [class.clear]="!item.strongestActiveAction">{{item.strongestActiveAction || 'CLEAR'}}</span></td><td>{{scopeLabels(item.activeScopes)}}</td></tr>}
        </tbody></table></div><nav aria-label="Business list pagination"><button [disabled]="result.page===0" (click)="move(-1)">Previous</button><span>Page {{result.page+1}} of {{result.totalPages || 1}}</span><button [disabled]="result.page+1>=result.totalPages" (click)="move(1)">Next</button></nav>}
      }
    </section>`,
  styles: [`
    .business-list{display:grid;gap:1rem}.business-list>header{display:flex;align-items:end;justify-content:space-between}.business-list>header p{margin:0;color:var(--color-info);font-size:.72rem;font-weight:850;text-transform:uppercase;letter-spacing:.08em}h2{margin:.2rem 0 0;font-size:1.65rem}header span,small{color:var(--color-text-muted)}
    .filters{display:grid;grid-template-columns:2fr repeat(4,minmax(125px,1fr)) auto;gap:.65rem;align-items:end;padding:1rem;border:1px solid var(--color-border);border-radius:var(--radius-md);background:var(--color-bg-secondary)}label{display:grid;gap:.3rem;color:var(--color-text-muted);font-size:.72rem;font-weight:750}input,select,button{min-height:42px;border:1px solid var(--color-border);border-radius:var(--radius-md);background:var(--color-bg-primary);color:var(--color-text-primary);padding:.55rem;font:inherit}button{border-color:var(--color-info);color:var(--color-info);font-weight:800;cursor:pointer}button:disabled{opacity:.4}.state,.table-wrap{border:1px solid var(--color-border);border-radius:var(--radius-md);background:rgba(18,19,23,.9)}.state{padding:1rem}.error{color:var(--color-danger)}.table-wrap{overflow:auto}table{width:100%;min-width:970px;border-collapse:collapse}th,td{padding:.75rem;text-align:left;border-bottom:1px solid var(--color-border);font-size:.8rem}th{color:var(--color-text-muted);font-size:.68rem;text-transform:uppercase}td a{color:var(--color-text-primary);font-weight:800;text-decoration:none}small{display:block;margin-top:.2rem;font-size:.66rem}.pill{display:inline-flex;padding:.2rem .5rem;border-radius:999px;background:rgba(251,146,60,.12);color:#fdba74;font-weight:850}.pill.clear{background:rgba(74,222,128,.1);color:#86efac}nav{display:flex;justify-content:flex-end;align-items:center;gap:.75rem;color:var(--color-text-muted);font-size:.78rem}@media(max-width:1000px){.filters{grid-template-columns:1fr 1fr}.search{grid-column:1/-1}}@media(max-width:560px){.filters{grid-template-columns:1fr}.search{grid-column:auto}.business-list>header{align-items:start;flex-direction:column;gap:.5rem}nav{justify-content:space-between}}
  `],
})
export class AdminBusinessListComponent implements OnInit {
  private readonly service=inject(AdminBusinessService); readonly page=signal<AdminBusinessSearchPage|null>(null); readonly loading=signal(true); readonly error=signal('');
  query=''; businessState=''; enforcementState=''; scope:BusinessEnforcementScope|''=''; sort='createdAt,desc'; private currentPage=0;
  ngOnInit(){this.load();} applyFilters(){this.currentPage=0;this.load();} move(delta:number){this.currentPage+=delta;this.load();}
  load(){this.loading.set(true);this.error.set('');this.service.search({q:this.query.trim(),businessState:this.businessState,enforcementState:this.enforcementState,scope:this.scope,page:this.currentPage,size:25,sort:this.sort}).subscribe({next:r=>{this.page.set(r.data);this.loading.set(false);},error:r=>{this.error.set(r.status===403?'You do not have permission to read businesses.':'Business search could not be loaded.');this.loading.set(false);}});}
  scopeLabels(scopes:BusinessEnforcementScope[]){return scopes.length?scopes.map(s=>s.replace('BUSINESS_','').replaceAll('_',' ')).join(', '):'—';}
}
