import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { CreateDisputeRequest, DisputeDetail, DisputePage, DisputeSummary } from '../models/order-dispute.model';

@Injectable({providedIn:'root'})
export class OrderDisputeService {
  private readonly http=inject(HttpClient);
  buyerList(orderId:string){return this.http.get<DisputeSummary[]>(`/api/v1/orders/${orderId}/disputes`,{withCredentials:true});}
  buyerCreate(orderId:string,body:CreateDisputeRequest){return this.http.post<DisputeDetail>(`/api/v1/orders/${orderId}/disputes`,body,this.options(this.key('buyer-dispute')));}
  buyerDetail(id:string){return this.http.get<DisputeDetail>(`/api/v1/disputes/${id}`,{withCredentials:true});}
  buyerStatement(id:string,body:string){return this.http.post<DisputeDetail>(`/api/v1/disputes/${id}/statements`,{body,evidence:[]},this.options(this.key('buyer-statement')));}
  sellerDetail(businessId:string,id:string){return this.http.get<DisputeDetail>(`/api/v1/businesses/${businessId}/disputes/${id}`,{withCredentials:true});}
  sellerStatement(businessId:string,id:string,body:string){return this.http.post<DisputeDetail>(`/api/v1/businesses/${businessId}/disputes/${id}/statements`,{body,evidence:[]},this.options(this.key('seller-statement')));}
  adminSearch(filters:Record<string,string|number|null|undefined>){let params=new HttpParams();Object.entries(filters).forEach(([k,v])=>{if(v!==null&&v!==undefined&&v!=='')params=params.set(k,String(v));});return this.http.get<DisputePage>('/api/v1/admin/disputes',{params,withCredentials:true});}
  adminDetail(id:string){return this.http.get<DisputeDetail>(`/api/v1/admin/disputes/${id}`,{withCredentials:true});}
  claim(id:string,v:number){return this.post(id,'claim',{expectedVersion:v});} release(id:string,v:number){return this.post(id,'release',{expectedVersion:v});}
  priority(id:string,v:number,priority:string,reason:string){return this.http.patch<DisputeDetail>(`/api/v1/admin/disputes/${id}/priority`,{expectedVersion:v,priority,reason},{withCredentials:true});}
  note(id:string,body:string){return this.post(id,'notes',{body},this.key('dispute-note'));}
  information(id:string,v:number,party:string,message:string){return this.post(id,'request-information',{expectedVersion:v,party,message},this.key('dispute-info'));}
  ready(id:string,v:number,reason:string){return this.post(id,'ready-for-decision',{expectedVersion:v,reason});}
  resolve(id:string,body:object){return this.post(id,'resolve',body,this.key('dispute-resolution'));}
  private post(id:string,action:string,body:object,key?:string){return this.http.post<DisputeDetail>(`/api/v1/admin/disputes/${id}/${action}`,body,key?this.options(key):{withCredentials:true});}
  private options(key:string){return{withCredentials:true,headers:new HttpHeaders({'Idempotency-Key':key})};}
  private key(prefix:string){return `${prefix}:${globalThis.crypto?.randomUUID?.()??Date.now()}`;}
}
