import { DatePipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { NotificationItem } from '../../core/models/notification.model';
import { BusinessStoreService } from '../../core/services/business-store.service';
import { NotificationService } from '../../core/services/notification.service';

@Component({
  selector: 'app-business-notification-center', standalone: true, imports: [DatePipe, RouterLink],
  template: `
    <section class="center">
      <header><div><p class="eyebrow">Seller updates</p><h2>Notifications</h2></div>
        <button type="button" (click)="markAll()" [disabled]="busy()">Mark all as read</button></header>
      @if (loading()) { <p class="state">Loading notifications...</p> }
      @else if (error()) { <p class="state error">{{ error() }}</p> }
      @else if (items().length === 0) { <p class="state">No seller notifications yet.</p> }
      @else { <ol>@for (item of items(); track item.id) {
        <li [class.unread]="!item.read"><div><strong>{{ title(item) }}</strong><p>{{ body(item) }}</p>
          <time>{{ item.createdAt | date:'medium' }}</time></div><div class="actions">
          @if (!item.read) { <button type="button" (click)="mark(item)" [disabled]="busy()">Mark read</button> }
          <a [routerLink]="item.safeRoute" (click)="mark(item)">Open order</a></div></li>
      }</ol> }
    </section>`,
  styles: [`.center{max-width:900px;margin:0 auto}.center>header{display:flex;align-items:center;justify-content:space-between;gap:1rem;margin-bottom:1rem}.eyebrow{color:var(--color-accent);font-size:.75rem;font-weight:800;text-transform:uppercase}h2{font-size:1.7rem}button,a{border:1px solid var(--color-border);border-radius:.55rem;padding:.55rem .75rem;background:var(--color-bg-secondary);color:var(--color-text-primary);font:inherit;text-decoration:none;cursor:pointer}ol{display:grid;gap:.75rem;list-style:none;padding:0}li{display:flex;justify-content:space-between;gap:1rem;padding:1rem;border:1px solid var(--color-border);border-radius:.8rem;background:var(--color-bg-secondary)}li.unread{border-left:4px solid var(--color-accent)}li p{margin:.35rem 0;color:var(--color-text-secondary)}time{color:var(--color-text-muted);font-size:.78rem}.actions{display:flex;align-items:center;gap:.5rem}.state{padding:2rem;text-align:center}.error{color:var(--color-danger)}@media(max-width:640px){.center>header,li{align-items:stretch;flex-direction:column}.actions>*{flex:1;text-align:center}}`],
})
export class BusinessNotificationCenterComponent implements OnInit {
  private readonly stores = inject(BusinessStoreService);
  private readonly notifications = inject(NotificationService);
  readonly items = signal<NotificationItem[]>([]); readonly loading = signal(true);
  readonly busy = signal(false); readonly error = signal<string | null>(null);
  private businessId: string | null = null;
  ngOnInit(): void { this.stores.getCurrentStoreContext().subscribe({ next: context => {
    this.businessId = context?.businessId || null;
    if (!this.businessId) { this.loading.set(false); this.error.set('No active business is available.'); return; }
    this.notifications.businessList(this.businessId).subscribe({
      next: page => { this.items.set(page.items); this.loading.set(false); },
      error: () => { this.error.set('Seller notifications are temporarily unavailable.'); this.loading.set(false); },
    });
  }, error: () => { this.error.set('Seller notifications are temporarily unavailable.'); this.loading.set(false); } }); }
  mark(item: NotificationItem): void { if (!this.businessId || item.read || this.busy()) return; this.busy.set(true);
    this.notifications.businessMarkRead(this.businessId, item.id).subscribe({
      next: () => { this.items.update(items => items.map(value => value.id === item.id ? {...value, read:true} : value)); this.busy.set(false); },
      error: () => { this.error.set('The notification could not be updated.'); this.busy.set(false); },
    }); }
  markAll(): void { if (!this.businessId || this.busy()) return; this.busy.set(true);
    this.notifications.businessMarkAllRead(this.businessId).subscribe({
      next: () => { this.items.update(items => items.map(item => ({...item, read:true}))); this.busy.set(false); },
      error: () => { this.error.set('The notifications could not be updated.'); this.busy.set(false); },
    }); }
  title(item: NotificationItem): string { return item.type === 'SELLER_NEW_ORDER' ? 'New paid order' : item.type === 'SELLER_RETURN_REQUESTED' ? 'Return requested' : 'Order cancelled'; }
  body(item: NotificationItem): string { return item.type === 'SELLER_NEW_ORDER' ? 'You have a new order waiting for acceptance.' : item.type === 'SELLER_RETURN_REQUESTED' ? 'A buyer requested a return for this order.' : 'The buyer cancellation has completed for this order.'; }
}
