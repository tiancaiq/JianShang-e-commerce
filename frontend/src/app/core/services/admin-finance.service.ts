import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { environment } from '../../../environments/environment';
import { AdminPage, AdminPaymentDetail, AdminPaymentSummary, AdminRefundDetail, AdminRefundPreview, AdminRefundRequest, AdminRefundSubmission, AdminRefundSummary } from '../models/admin-finance.model';

export type FinanceFilter = Record<string,string|number|null|undefined>;

@Injectable({providedIn:'root'})
export class AdminFinanceService {
  private readonly base=`${environment.apiGatewayUrl}/api/v1/admin`;
  constructor(private readonly http:HttpClient){}
  payments(filter:FinanceFilter){return this.http.get<AdminPage<AdminPaymentSummary>>(`${this.base}/payments`,{params:this.params(filter),withCredentials:true});}
  payment(id:string){return this.http.get<AdminPaymentDetail>(`${this.base}/payments/${id}`,{withCredentials:true});}
  refunds(filter:FinanceFilter){return this.http.get<AdminPage<AdminRefundSummary>>(`${this.base}/refunds`,{params:this.params(filter),withCredentials:true});}
  refund(id:string){return this.http.get<AdminRefundDetail>(`${this.base}/refunds/${id}`,{withCredentials:true});}
  preview(paymentId:string,request:AdminRefundRequest){return this.http.post<AdminRefundPreview>(`${this.base}/payments/${paymentId}/refund/dry-run`,request,{withCredentials:true});}
  execute(paymentId:string,request:AdminRefundRequest){return this.http.post<AdminRefundSubmission>(`${this.base}/payments/${paymentId}/refund`,request,{withCredentials:true,headers:new HttpHeaders({'Idempotency-Key':request.idempotencyKey})});}
  private params(filter:FinanceFilter){let params=new HttpParams();for(const [key,value] of Object.entries(filter))if(value!==null&&value!==undefined&&value!=='')params=params.set(key,String(value));return params;}
}
