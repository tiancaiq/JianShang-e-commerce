import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { AdminOrderPage } from '../../core/models/admin-order.model';
import { AdminOrderSearch, AdminOrderService } from '../../core/services/admin-order.service';

@Component({
  selector: 'app-admin-order-list',
  standalone: true,
  imports: [FormsModule, RouterLink, DatePipe, DecimalPipe],
  template: `
    <header class="page-heading">
      <div><p class="eyebrow">Commerce operations</p><h1>Orders</h1>
        <p>Inspect purchase-time records across payment, inventory, and fulfillment.</p></div>
      @if (page(); as result) { <span class="count">{{ result.totalElements }} orders</span> }
    </header>

    <form class="filters" (ngSubmit)="applyFilters()">
      <label class="query">Order ID <input name="q" [(ngModel)]="q" placeholder="Search order ID" /></label>
      <label>Order status <select name="status" [(ngModel)]="status"><option value="">All</option>
        <option>CONFIRMED</option><option>CANCELLATION_REQUESTED</option><option>CANCELLED</option></select></label>
      <label>Fulfillment <select name="fulfillment" [(ngModel)]="fulfillmentStatus"><option value="">All</option>
        <option>PENDING_ACCEPTANCE</option><option>ACCEPTED</option><option>PROCESSING</option>
        <option>SHIPPED</option><option>DELIVERED</option></select></label>
      <label>Buyer ID <input name="buyer" [(ngModel)]="buyerUserId" placeholder="ULID" /></label>
      <label>Business ID <input name="business" [(ngModel)]="businessId" placeholder="ULID" /></label>
      <label>Listing ID <input name="listing" [(ngModel)]="listingId" placeholder="ULID" /></label>
      <label>Created from <input name="createdFrom" type="datetime-local" [(ngModel)]="createdFrom" /></label>
      <label>Created to <input name="createdTo" type="datetime-local" [(ngModel)]="createdTo" /></label>
      <label>Sort <select name="sort" [(ngModel)]="sort"><option value="createdAt,desc">Newest first</option>
        <option value="createdAt,asc">Oldest first</option><option value="updatedAt,desc">Recently updated</option>
        <option value="total,desc">Highest total</option><option value="total,asc">Lowest total</option></select></label>
      <div class="filter-actions"><button type="button" class="secondary" (click)="clear()">Clear</button>
        <button type="submit">Search orders</button></div>
    </form>

    @if (loading()) { <section class="state">Loading orders…</section> }
    @else if (error()) { <section class="state error" role="alert">{{ error() }}</section> }
    @else if (page(); as result) {
      @if (result.content.length === 0) { <section class="state">No orders match these filters.</section> }
      @else {
        <div class="table-shell"><table><thead><tr><th>Order</th><th>Buyer</th><th>Seller / business</th>
          <th>Amount</th><th>Payment</th><th>Lifecycle</th><th>Created</th></tr></thead><tbody>
          @for (order of result.content; track order.orderId) { <tr>
            <td><a [routerLink]="['/admin/orders', order.orderId]">{{ shortId(order.orderId) }}</a>
              <small>{{ order.itemCount }} item{{ order.itemCount === 1 ? '' : 's' }} · v{{ order.version }}</small></td>
            <td>{{ order.buyerDisplayName || shortId(order.buyerUserId) }}<small>{{ shortId(order.buyerUserId) }}</small></td>
            <td>{{ order.businessDisplayNames.join(', ') }}<small>{{ order.businessIds.length }} business group{{ order.businessIds.length === 1 ? '' : 's' }}</small></td>
            <td class="amount">{{ order.totalAmount | number: '1.2-2' }} {{ order.currency }}</td>
            <td><span class="pill" [class.good]="order.paymentStatus === 'SUCCEEDED'">{{ label(order.paymentStatus) }}</span></td>
            <td><strong>{{ label(order.status) }}</strong><small>{{ label(order.fulfillmentStatus) }}</small></td>
            <td>{{ order.createdAt | date: 'medium' }}</td>
          </tr> }
        </tbody></table></div>
        <nav class="pagination" aria-label="Order pages"><button type="button" class="secondary"
          [disabled]="result.page === 0" (click)="go(result.page - 1)">Previous</button>
          <span>Page {{ result.page + 1 }} of {{ result.totalPages }}</span><button type="button" class="secondary"
          [disabled]="result.page + 1 >= result.totalPages" (click)="go(result.page + 1)">Next</button></nav>
      }
    }
  `,
  styles: [`
    :host{display:grid;gap:1rem}.page-heading{display:flex;justify-content:space-between;align-items:end;gap:1rem}.page-heading h1{margin:.1rem 0;font-size:1.75rem}.page-heading p{margin:0;color:var(--color-text-secondary)}.eyebrow{color:var(--color-info)!important;font-size:.72rem;font-weight:850;letter-spacing:.12em;text-transform:uppercase}.count{border:1px solid var(--color-border);border-radius:999px;padding:.4rem .7rem;color:var(--color-text-secondary);font-size:.78rem}
    .filters{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:.7rem;padding:1rem;border:1px solid var(--color-border);border-radius:var(--radius-md);background:var(--color-bg-secondary)}label{display:grid;gap:.3rem;color:var(--color-text-muted);font-size:.72rem;font-weight:750}.query{grid-column:span 2}input,select{min-width:0;border:1px solid var(--color-border);border-radius:var(--radius-md);background:var(--color-bg-primary);color:var(--color-text-primary);padding:.6rem;font:inherit}.filter-actions{display:flex;align-items:end;justify-content:flex-end;gap:.5rem}button{min-height:40px;border:1px solid var(--color-info);border-radius:var(--radius-md);background:rgba(56,189,248,.12);color:var(--color-info);padding:.5rem .8rem;font:inherit;font-weight:800;cursor:pointer}.secondary{border-color:var(--color-border);background:transparent;color:var(--color-text-secondary)}button:disabled{opacity:.4;cursor:not-allowed}
    .table-shell{overflow:auto;border:1px solid var(--color-border);border-radius:var(--radius-md)}table{width:100%;min-width:900px;border-collapse:collapse;background:var(--color-bg-secondary)}th,td{text-align:left;padding:.75rem;border-bottom:1px solid var(--color-border);font-size:.78rem;vertical-align:top}th{color:var(--color-text-muted);font-size:.68rem;letter-spacing:.05em;text-transform:uppercase}td{color:var(--color-text-secondary)}td a,td strong{color:var(--color-text-primary);font-weight:800;text-decoration:none}td a:hover{color:var(--color-info)}small{display:block;margin-top:.25rem;color:var(--color-text-muted);font-size:.68rem}.amount{font-variant-numeric:tabular-nums;color:var(--color-text-primary);font-weight:750}.pill{display:inline-flex;border:1px solid var(--color-border);border-radius:999px;padding:.2rem .45rem;font-size:.66rem}.pill.good{border-color:rgba(74,222,128,.4);color:#86efac}.state{padding:2rem;border:1px dashed var(--color-border);border-radius:var(--radius-md);color:var(--color-text-muted);text-align:center}.state.error{color:var(--color-danger)}.pagination{display:flex;justify-content:flex-end;align-items:center;gap:.8rem}.pagination span{color:var(--color-text-muted);font-size:.75rem}
    @media(max-width:800px){.filters{grid-template-columns:1fr 1fr}.query{grid-column:1/-1}}@media(max-width:560px){.page-heading{align-items:start;flex-direction:column}.filters{grid-template-columns:1fr}.query{grid-column:auto}.filter-actions{justify-content:stretch}.filter-actions button{flex:1}}
  `],
})
export class AdminOrderListComponent implements OnInit {
  private readonly service = inject(AdminOrderService);
  private readonly route = inject(ActivatedRoute);
  private readonly destroy = inject(DestroyRef);
  readonly page = signal<AdminOrderPage | null>(null);
  readonly loading = signal(true); readonly error = signal('');
  q=''; status=''; fulfillmentStatus=''; buyerUserId=''; businessId=''; listingId='';
  createdFrom=''; createdTo=''; sort='createdAt,desc'; private currentPage=0;
  private readonly statuses=['CONFIRMED','CANCELLATION_REQUESTED','CANCELLED'];
  private readonly fulfillmentStatuses=['PENDING_ACCEPTANCE','ACCEPTED','PROCESSING','SHIPPED','DELIVERED'];
  ngOnInit(): void { this.route.queryParamMap.pipe(takeUntilDestroyed(this.destroy)).subscribe(params=>{
    const status=params.get('status');const fulfillment=params.get('fulfillmentStatus');
    this.status=status&&this.statuses.includes(status)?status:'';
    this.fulfillmentStatus=fulfillment&&this.fulfillmentStatuses.includes(fulfillment)?fulfillment:'';
    this.currentPage=0;this.load();
  }); }
  applyFilters(): void { this.currentPage=0; this.load(); }
  go(page: number): void { this.currentPage=page; this.load(); }
  clear(): void { this.q='';this.status='';this.fulfillmentStatus='';this.buyerUserId='';this.businessId='';
    this.listingId='';this.createdFrom='';this.createdTo='';this.sort='createdAt,desc';this.applyFilters(); }
  private load(): void {
    this.loading.set(true); this.error.set('');
    const filter: AdminOrderSearch={q:this.q.trim(),status:this.status,fulfillmentStatus:this.fulfillmentStatus,
      buyerUserId:this.buyerUserId.trim(),businessId:this.businessId.trim(),listingId:this.listingId.trim(),
      createdFrom:this.iso(this.createdFrom),createdTo:this.iso(this.createdTo),page:this.currentPage,size:25,sort:this.sort};
    this.service.search(filter).subscribe({next:value=>{this.page.set(value);this.loading.set(false)},
      error:(response:HttpErrorResponse)=>{this.error.set(response.status===403?'You do not have permission to read orders.':response.error?.error?.message||'Orders could not be loaded.');this.loading.set(false)}});
  }
  shortId(value:string):string{return value.length>12?`${value.slice(0,6)}…${value.slice(-4)}`:value}
  label(value:string):string{return value.toLowerCase().replaceAll('_',' ').replace(/^./,c=>c.toUpperCase())}
  private iso(value:string):string|undefined{return value?new Date(value).toISOString():undefined}
}
