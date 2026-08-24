import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { environment } from '../../../environments/environment';
import { FeatureState, InventoryIssue, JobSummary, MaintenancePreview, MaintenanceRequest, MaintenanceResult, OutboxSummary, PageResponse, ReconciliationIssue, SearchStatus, ServiceHealth, SystemSummary } from '../models/system-operations.model';
@Injectable({providedIn:'root'})
export class SystemOperationsService {
 private readonly base=`${environment.apiGatewayUrl}/api/v1/admin/system`;
 constructor(private readonly http:HttpClient){}
 summary(){return this.http.get<SystemSummary>(`${this.base}/summary`);}
 health(){return this.http.get<ServiceHealth[]>(`${this.base}/health`);}
 jobs(filters:Record<string,string|boolean|number|undefined>={}){return this.http.get<PageResponse<JobSummary>>(`${this.base}/jobs`,{params:this.params(filters)});}
 job(id:string){return this.http.get<JobSummary>(`${this.base}/jobs/${encodeURIComponent(id)}`);}
 outbox(filters:Record<string,string|boolean|number|undefined>={}){return this.http.get<PageResponse<OutboxSummary>>(`${this.base}/outbox`,{params:this.params(filters)});}
 outboxEvent(id:string){return this.http.get<OutboxSummary>(`${this.base}/outbox/${encodeURIComponent(id)}`);}
 reconciliation(filters:Record<string,string|boolean|number|undefined>={}){return this.http.get<PageResponse<ReconciliationIssue>>(`${this.base}/reconciliation`,{params:this.params(filters)});}
 inventory(filters:Record<string,string|boolean|number|undefined>={}){return this.http.get<PageResponse<InventoryIssue>>(`${this.base}/inventory`,{params:this.params(filters)});}
 search(){return this.http.get<SearchStatus[]>(`${this.base}/search`);}
 features(){return this.http.get<FeatureState[]>(`${this.base}/features`);}
 previewJob(id:string,reason:string){return this.http.post<MaintenancePreview>(`${this.base}/jobs/${encodeURIComponent(id)}/retry/dry-run`,{reason});}
 retryJob(id:string,request:MaintenanceRequest,key:string){return this.command(`${this.base}/jobs/${encodeURIComponent(id)}/retry`,request,key);}
 previewOutbox(id:string,reason:string){return this.http.post<MaintenancePreview>(`${this.base}/outbox/${encodeURIComponent(id)}/retry/dry-run`,{reason});}
 retryOutbox(id:string,request:MaintenanceRequest,key:string){return this.command(`${this.base}/outbox/${encodeURIComponent(id)}/retry`,request,key);}
 previewReindex(id:string,reason:string){return this.http.post<MaintenancePreview>(`${this.base}/search/listings/${encodeURIComponent(id)}/reindex/dry-run`,{reason});}
 reindex(id:string,request:MaintenanceRequest,key:string){return this.command(`${this.base}/search/listings/${encodeURIComponent(id)}/reindex`,request,key);}
 private command(url:string,request:MaintenanceRequest,key:string){return this.http.post<MaintenanceResult>(url,request,{headers:new HttpHeaders({'Idempotency-Key':key})});}
 private params(values:Record<string,string|boolean|number|undefined>){let result=new HttpParams();Object.entries(values).forEach(([key,value])=>{if(value!==undefined&&value!=='')result=result.set(key,String(value));});return result;}
}
