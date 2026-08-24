import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { map } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ApiDataResponse } from '../models/auth.model';
import { AdminSupportDetail, AdminSupportPage, CreateSupportTicketRequest, SupportAssignment, SupportCategory, SupportPriority, SupportResolutionCode, SupportStatus, SupportTargetType, SupportUserDetail, SupportUserPage } from '../models/support.model';

@Injectable({providedIn:'root'})
export class SupportService {
  private readonly http=inject(HttpClient);
  private readonly userUrl=`${environment.apiGatewayUrl}/api/v1/support/tickets`;
  private readonly adminUrl=`${environment.apiGatewayUrl}/api/v1/admin/support/tickets`;
  create(body:CreateSupportTicketRequest){return this.http.post<ApiDataResponse<SupportUserDetail>>(this.userUrl,body,this.options('support-create')).pipe(map(r=>r.data));}
  mine(page=0,size=25){return this.http.get<ApiDataResponse<SupportUserPage>>(`${this.userUrl}/mine`,{params:{page,size},withCredentials:true}).pipe(map(r=>r.data));}
  userDetail(id:string){return this.http.get<ApiDataResponse<SupportUserDetail>>(`${this.userUrl}/${id}`,{withCredentials:true}).pipe(map(r=>r.data));}
  userReply(id:string,body:string,expectedVersion:number){return this.http.post<ApiDataResponse<SupportUserDetail>>(`${this.userUrl}/${id}/messages`,{body,expectedVersion},this.options('support-user-message')).pipe(map(r=>r.data));}
  search(filters:{q?:string;requesterUserId?:string;category?:SupportCategory|'';status?:SupportStatus|'';priority?:SupportPriority|'';assignment?:SupportAssignment;linkedOrderId?:string;linkedBusinessId?:string;createdFrom?:string;createdTo?:string;page:number;size:number;sort:string}){let params=new HttpParams();Object.entries(filters).forEach(([key,value])=>{if(value!==undefined&&value!==null&&value!=='')params=params.set(key,String(value));});return this.http.get<ApiDataResponse<AdminSupportPage>>(this.adminUrl,{params,withCredentials:true}).pipe(map(r=>r.data));}
  adminDetail(id:string){return this.http.get<ApiDataResponse<AdminSupportDetail>>(`${this.adminUrl}/${id}`,{withCredentials:true}).pipe(map(r=>r.data));}
  claim(id:string,v:number){return this.post(id,'claim',{expectedVersion:v});}
  release(id:string,v:number){return this.post(id,'release',{expectedVersion:v});}
  respond(id:string,body:string,v:number){return this.post(id,'messages',{body,expectedVersion:v},'support-admin-message');}
  requestInformation(id:string,body:string,v:number){return this.post(id,'request-information',{body,expectedVersion:v},'support-request-info');}
  note(id:string,body:string,v:number){return this.post(id,'notes',{body,expectedVersion:v},'support-note');}
  priority(id:string,priority:SupportPriority,reason:string,v:number){return this.http.patch<ApiDataResponse<AdminSupportDetail>>(`${this.adminUrl}/${id}/priority`,{priority,reason,expectedVersion:v},{withCredentials:true}).pipe(map(r=>r.data));}
  link(id:string,targetType:SupportTargetType,targetId:string,v:number){return this.post(id,'links',{targetType,targetId,relationType:'RELATED',expectedVersion:v});}
  unlink(id:string,targetType:SupportTargetType,targetId:string,v:number){return this.post(id,`links/${targetType}/${targetId}/unlink`,{expectedVersion:v,reason:'No longer relevant'});}
  escalate(id:string,destinationType:string,destinationId:string,reason:string,v:number){return this.post(id,'escalations',{destinationType,destinationId,reason,expectedVersion:v},'support-escalation');}
  resolve(id:string,resolutionCode:SupportResolutionCode,reason:string,v:number){return this.post(id,'resolve',{resolutionCode,reason,expectedVersion:v},'support-resolution');}
  private post(id:string,action:string,body:object,keyPrefix?:string){return this.http.post<ApiDataResponse<AdminSupportDetail>>(`${this.adminUrl}/${id}/${action}`,body,keyPrefix?this.options(keyPrefix):{withCredentials:true}).pipe(map(r=>r.data));}
  private options(prefix:string){return {withCredentials:true,headers:new HttpHeaders({'Idempotency-Key':`${prefix}:${globalThis.crypto?.randomUUID?.()??Date.now()}`})};}
}
