import {HttpClient,HttpHeaders,HttpParams} from '@angular/common/http';
import {Injectable} from '@angular/core';
import {environment} from '../../../environments/environment';
import {AdminGovernanceDetail,AdminGovernanceSummary,ApprovalDetail,ApprovalSummary,GovernanceDashboard,GovernancePage,GrantRoleRequest,RevokeRoleRequest,RoleChangePreview,RoleChangeResult,RoleDefinition} from '../models/admin-governance.model';

@Injectable({providedIn:'root'})
export class AdminGovernanceService{
 private readonly base=`${environment.apiGatewayUrl}/api/v1/admin/governance`;
 constructor(private readonly http:HttpClient){}
 dashboard(){return this.http.get<GovernanceDashboard>(this.base,{withCredentials:true});}
 admins(filter:Record<string,unknown>){return this.http.get<GovernancePage<AdminGovernanceSummary>>(`${this.base}/admins`,{params:this.params(filter),withCredentials:true});}
 admin(id:string){return this.http.get<AdminGovernanceDetail>(`${this.base}/admins/${id}`,{withCredentials:true});}
 roles(){return this.http.get<RoleDefinition[]>(`${this.base}/roles`,{withCredentials:true});}
 previewGrant(id:string,body:GrantRoleRequest){return this.http.post<RoleChangePreview>(`${this.base}/admins/${id}/roles/dry-run`,body,{withCredentials:true});}
 grant(id:string,body:GrantRoleRequest){return this.http.post<RoleChangeResult>(`${this.base}/admins/${id}/roles`,body,{withCredentials:true,headers:this.key(body.idempotencyKey)});}
 previewRevoke(adminId:string,assignmentId:string,body:RevokeRoleRequest){return this.http.post<RoleChangePreview>(`${this.base}/admins/${adminId}/roles/${assignmentId}/revoke/dry-run`,body,{withCredentials:true});}
 revoke(adminId:string,assignmentId:string,body:RevokeRoleRequest){return this.http.post<RoleChangeResult>(`${this.base}/admins/${adminId}/roles/${assignmentId}/revoke`,body,{withCredentials:true,headers:this.key(body.idempotencyKey)});}
 approvals(filter:Record<string,unknown>){return this.http.get<GovernancePage<ApprovalSummary>>(`${this.base}/approvals`,{params:this.params(filter),withCredentials:true});}
 approval(id:string){return this.http.get<ApprovalDetail>(`${this.base}/approvals/${id}`,{withCredentials:true});}
 decide(id:string,decision:'approve'|'reject',version:number,reason:string){const key=crypto.randomUUID();return this.http.post<ApprovalDetail>(`${this.base}/approvals/${id}/${decision}`,{expectedVersion:version,reason,idempotencyKey:key},{withCredentials:true,headers:this.key(key)});}
 cancel(id:string,version:number,reason:string){const key=crypto.randomUUID();return this.http.post<ApprovalDetail>(`${this.base}/approvals/${id}/cancel`,{expectedVersion:version,reason,idempotencyKey:key},{withCredentials:true,headers:this.key(key)});}
 execute(id:string,version:number){const key=crypto.randomUUID();return this.http.post<ApprovalDetail>(`${this.base}/approvals/${id}/execute`,{expectedApprovalVersion:version,idempotencyKey:key},{withCredentials:true,headers:this.key(key)});}
 private key(value:string){return new HttpHeaders({'Idempotency-Key':value});}
 private params(filter:Record<string,unknown>){let result=new HttpParams();for(const[k,v]of Object.entries(filter))if(v!==null&&v!==undefined&&v!=='')result=result.set(k,String(v));return result;}
}
