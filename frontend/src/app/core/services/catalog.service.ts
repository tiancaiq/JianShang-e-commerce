import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { CatalogApproval, CatalogDetail, CatalogImpact, CatalogOverview, CategoryStatus, SellerEligibility } from '../models/catalog.model';

@Injectable({providedIn:'root'})
export class CatalogService {
  private readonly base=`${environment.apiGatewayUrl}/api/v1/admin/catalog/categories`;
  constructor(private readonly http:HttpClient){}
  overview(filters:{q?:string;status?:CategoryStatus|'';sellerEligibility?:SellerEligibility|''}={}):Observable<CatalogOverview>{
    let params=new HttpParams();
    if(filters.q?.trim())params=params.set('q',filters.q.trim());
    if(filters.status)params=params.set('status',filters.status);
    if(filters.sellerEligibility)params=params.set('sellerEligibility',filters.sellerEligibility);
    return this.http.get<CatalogOverview>(this.base,{params,withCredentials:true});
  }
  detail(id:string){return this.http.get<CatalogDetail>(`${this.base}/${id}`,{withCredentials:true});}
  create(request:unknown){return this.http.post<CatalogDetail>(this.base,request,{headers:this.idempotency(),withCredentials:true});}
  update(id:string,request:unknown){return this.http.patch<CatalogDetail>(`${this.base}/${id}`,request,{withCredentials:true});}
  movePreview(id:string,request:unknown){return this.http.post<CatalogImpact>(`${this.base}/${id}/move/dry-run`,request,{withCredentials:true});}
  move(id:string,request:unknown){return this.http.post<CatalogDetail>(`${this.base}/${id}/move`,request,{withCredentials:true});}
  statusPreview(id:string,request:unknown){return this.http.post<CatalogImpact>(`${this.base}/${id}/status/dry-run`,request,{withCredentials:true});}
  changeStatus(id:string,request:unknown){return this.http.post<CatalogDetail|CatalogApproval>(`${this.base}/${id}/status`,request,{headers:this.idempotency(),withCredentials:true});}
  policyPreview(id:string,request:unknown){return this.http.post<CatalogImpact>(`${this.base}/${id}/policy/dry-run`,request,{withCredentials:true});}
  publishPolicy(id:string,request:unknown){return this.http.post<CatalogDetail>(`${this.base}/${id}/policy`,request,{withCredentials:true});}
  createAttribute(id:string,request:unknown){return this.http.post<CatalogDetail>(`${this.base}/${id}/attributes`,request,{headers:this.idempotency(),withCredentials:true});}
  createAttributePreview(id:string,request:unknown){return this.http.post<CatalogImpact>(`${this.base}/${id}/attributes/create/dry-run`,request,{withCredentials:true});}
  attributePreview(id:string,attributeId:string,request:unknown){return this.http.post<CatalogImpact>(`${this.base}/${id}/attributes/dry-run`,request,{params:{attributeId},withCredentials:true});}
  updateAttribute(id:string,attributeId:string,request:unknown){return this.http.patch<CatalogDetail>(`${this.base}/${id}/attributes/${attributeId}`,request,{withCredentials:true});}
  attributeStatusPreview(id:string,attributeId:string,request:unknown){return this.http.post<CatalogImpact>(`${this.base}/${id}/attributes/${attributeId}/status/dry-run`,request,{withCredentials:true});}
  attributeStatus(id:string,attributeId:string,request:unknown){return this.http.post<CatalogDetail>(`${this.base}/${id}/attributes/${attributeId}/status`,request,{withCredentials:true});}
  createOption(id:string,attributeId:string,request:unknown){return this.http.post<CatalogDetail>(`${this.base}/${id}/attributes/${attributeId}/options`,request,{withCredentials:true});}
  optionStatusPreview(id:string,attributeId:string,optionId:string,request:unknown){return this.http.post<CatalogImpact>(`${this.base}/${id}/attributes/${attributeId}/options/${optionId}/status/dry-run`,request,{withCredentials:true});}
  optionStatus(id:string,attributeId:string,optionId:string,request:unknown){return this.http.post<CatalogDetail>(`${this.base}/${id}/attributes/${attributeId}/options/${optionId}/status`,request,{withCredentials:true});}
  createGuidance(id:string,request:unknown){return this.http.post<CatalogDetail>(`${this.base}/${id}/guidance`,request,{withCredentials:true});}
  updateGuidance(id:string,guidanceId:string,request:unknown){return this.http.patch<CatalogDetail>(`${this.base}/${id}/guidance/${guidanceId}`,request,{withCredentials:true});}
  guidanceStatus(id:string,guidanceId:string,request:unknown){return this.http.post<CatalogDetail>(`${this.base}/${id}/guidance/${guidanceId}/status`,request,{withCredentials:true});}
  private idempotency(){return new HttpHeaders({'Idempotency-Key':`catalog-${Date.now()}-${Math.random().toString(36).slice(2,10)}`});}
}
