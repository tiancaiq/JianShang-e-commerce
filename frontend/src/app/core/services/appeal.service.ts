import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { map } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ApiDataResponse } from '../models/auth.model';
import { AppealAssignment, AppealDetail, AppealOutcome, AppealPage, AppealReason,
  AppealResolutionPreview, AppealResolutionPreviewRequest, AppealResolutionRequest, AppealStatus,
  EnforcementNotice, MyAppeal, ReplacementProposal } from '../models/appeal.model';
import { ReportTargetType } from '../models/report.model';

@Injectable({ providedIn: 'root' })
export class AppealService {
  private readonly api = environment.apiGatewayUrl;
  constructor(private readonly http: HttpClient) {}
  notices() { return this.http.get<ApiDataResponse<EnforcementNotice[]>>(`${this.api}/api/v1/enforcements/mine`, { withCredentials: true }).pipe(map(r => r.data)); }
  mine() { return this.http.get<ApiDataResponse<MyAppeal[]>>(`${this.api}/api/v1/appeals/mine`, { withCredentials: true }).pipe(map(r => r.data)); }
  submit(actionId: string, reasonCode: AppealReason, explanation: string) {
    return this.http.post<ApiDataResponse<{appealId:string;status:AppealStatus;submittedAt:string;supportReference:string}>>(
      `${this.api}/api/v1/enforcements/${actionId}/appeals`, { reasonCode, explanation, safeEvidenceReferences: [] }, { withCredentials: true }).pipe(map(r => r.data));
  }
  search(filters: {q?:string;targetType?:ReportTargetType|'';status?:AppealStatus|'';reasonCode?:AppealReason|'';
    assignment:AppealAssignment;submittedFrom?:string;submittedTo?:string;page:number;size:number;sort:string}) {
    let params = new HttpParams().set('assignment', filters.assignment).set('page', filters.page).set('size', filters.size).set('sort', filters.sort);
    Object.entries(filters).forEach(([key,value]) => { if(!['assignment','page','size','sort'].includes(key) && value) params=params.set(key,String(value)); });
    return this.http.get<ApiDataResponse<AppealPage>>(`${this.api}/api/v1/admin/appeals`, {params,withCredentials:true}).pipe(map(r=>r.data));
  }
  detail(id:string){return this.http.get<ApiDataResponse<AppealDetail>>(`${this.api}/api/v1/admin/appeals/${id}`,{withCredentials:true}).pipe(map(r=>r.data));}
  claim(id:string,version:number){return this.post(id,'claim',{expectedVersion:version});}
  release(id:string,version:number){return this.post(id,'release',{expectedVersion:version});}
  start(id:string,version:number){return this.post(id,'start-review',{expectedVersion:version});}
  note(id:string,version:number,body:string){return this.post(id,'notes',{expectedVersion:version,body,idempotencyKey:crypto.randomUUID()});}
  review(id:string,version:number,outcome:AppealOutcome,reasonCode:string,reason:string,replacementProposal:ReplacementProposal|null){
    return this.post(id,'review',{expectedVersion:version,outcome,reasonCode,reason,replacementProposal});
  }
  previewResolution(id:string,request:AppealResolutionPreviewRequest){
    return this.http.post<ApiDataResponse<AppealResolutionPreview>>(
      `${this.api}/api/v1/admin/appeals/${id}/resolution/dry-run`,request,{withCredentials:true}).pipe(map(r=>r.data));
  }
  resolve(id:string,request:AppealResolutionRequest){
    return this.http.post<ApiDataResponse<AppealDetail>>(`${this.api}/api/v1/admin/appeals/${id}/resolution`,request,
      {withCredentials:true,headers:new HttpHeaders({'Idempotency-Key':request.idempotencyKey})}).pipe(map(r=>r.data));
  }
  private post(id:string,path:string,body:object){return this.http.post<ApiDataResponse<AppealDetail>>(`${this.api}/api/v1/admin/appeals/${id}/${path}`,body,{withCredentials:true}).pipe(map(r=>r.data));}
}
