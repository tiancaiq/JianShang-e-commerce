import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { UserProfileService } from '../../core/services/user-profile.service';
import { ToastService } from '../../core/services/toast.service';
import { CurrentUser, UpdateCurrentUserRequest } from '../../core/models/user.model';

@Component({
  selector: 'app-profile',
  standalone: true,
  imports: [FormsModule],
  template: `
    <section class="profile-page">
      <header class="profile-header">
        <div>
          <h1>Profile</h1>
          <p>{{ user()?.email || 'Signed in account' }}</p>
        </div>
        <span class="status-pill">{{ user()?.status || 'LOADING' }}</span>
      </header>

      <form class="profile-form" (ngSubmit)="save()">
        <div class="form-grid">
          <label class="field">
            <span>Display name</span>
            <input
              name="displayName"
              [(ngModel)]="displayName"
              maxlength="200"
              [disabled]="loading() || saving()"
            />
          </label>

          <label class="field">
            <span>Email</span>
            <input [value]="user()?.email || ''" disabled />
          </label>

          <label class="field">
            <span>Phone</span>
            <input
              name="phone"
              [(ngModel)]="phone"
              placeholder="+19495551234"
              [disabled]="loading() || saving()"
            />
          </label>

          <label class="field">
            <span>Avatar URL</span>
            <input
              name="avatarUrl"
              [(ngModel)]="avatarUrl"
              placeholder="https://example.com/avatar.png"
              maxlength="2048"
              [disabled]="loading() || saving()"
            />
          </label>
        </div>

        @if (errorMsg()) {
          <div class="error-message">{{ errorMsg() }}</div>
        }

        <div class="actions">
          <button type="button" class="secondary-btn" (click)="load()" [disabled]="loading() || saving()">
            Reset
          </button>
          <button type="submit" class="primary-btn" [disabled]="loading() || saving()">
            {{ saving() ? 'Saving' : 'Save' }}
          </button>
        </div>
      </form>
    </section>
  `,
  styles: [`
    .profile-page {
      display: flex;
      flex-direction: column;
      gap: 1.5rem;
      max-width: 860px;
    }

    .profile-header {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 1rem;
    }

    .profile-header h1 {
      font-size: 1.75rem;
      margin-bottom: 0.25rem;
    }

    .profile-header p {
      color: var(--color-text-muted);
      font-size: 0.875rem;
    }

    .status-pill {
      display: inline-flex;
      align-items: center;
      min-height: 28px;
      padding: 0 0.625rem;
      border-radius: var(--radius-md);
      background: var(--color-accent-muted);
      color: var(--color-accent);
      font-size: 0.75rem;
      font-weight: 700;
    }

    .profile-form {
      background: var(--color-bg-secondary);
      border: 1px solid var(--color-border);
      border-radius: var(--radius-lg);
      padding: 1.25rem;
      display: flex;
      flex-direction: column;
      gap: 1.25rem;
    }

    .form-grid {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 1rem;
    }

    .field {
      display: flex;
      flex-direction: column;
      gap: 0.375rem;
    }

    .field span {
      font-size: 0.8125rem;
      font-weight: 600;
      color: var(--color-text-secondary);
    }

    .field input {
      width: 100%;
      min-height: 40px;
      padding: 0.625rem 0.75rem;
      background: var(--color-bg-tertiary);
      border: 1px solid var(--color-border);
      border-radius: var(--radius-md);
      color: var(--color-text-primary);
      font-size: 0.875rem;
      outline: none;
    }

    .field input:focus {
      border-color: var(--color-accent);
      box-shadow: 0 0 0 3px var(--color-accent-muted);
    }

    .field input:disabled {
      opacity: 0.7;
      cursor: not-allowed;
    }

    .error-message {
      color: var(--color-danger);
      background: rgba(244, 63, 94, 0.08);
      border: 1px solid rgba(244, 63, 94, 0.2);
      border-radius: var(--radius-md);
      padding: 0.625rem 0.75rem;
      font-size: 0.875rem;
    }

    .actions {
      display: flex;
      justify-content: flex-end;
      gap: 0.75rem;
    }

    .primary-btn,
    .secondary-btn {
      min-width: 88px;
      min-height: 40px;
      padding: 0 0.875rem;
      border-radius: var(--radius-md);
      font-weight: 700;
      cursor: pointer;
      border: 1px solid transparent;
    }

    .primary-btn {
      background: var(--color-accent);
      color: #0c0c0e;
    }

    .secondary-btn {
      background: transparent;
      color: var(--color-text-secondary);
      border-color: var(--color-border);
    }

    .primary-btn:disabled,
    .secondary-btn:disabled {
      opacity: 0.55;
      cursor: not-allowed;
    }

    @media (max-width: 760px) {
      .profile-header {
        flex-direction: column;
      }

      .form-grid {
        grid-template-columns: 1fr;
      }

      .actions {
        justify-content: stretch;
      }

      .primary-btn,
      .secondary-btn {
        flex: 1;
      }
    }
  `]
})
export class ProfileComponent implements OnInit {
  private userProfileService = inject(UserProfileService);
  private toastService = inject(ToastService);
  private router = inject(Router);

  user = signal<CurrentUser | null>(null);
  loading = signal(false);
  saving = signal(false);
  errorMsg = signal('');

  displayName = '';
  phone = '';
  avatarUrl = '';

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.errorMsg.set('');

    this.userProfileService.getMe().subscribe({
      next: response => {
        this.applyUser(response.data);
        this.loading.set(false);
      },
      error: error => {
        this.loading.set(false);
        if (error.status === 401) {
          this.router.navigate(['/login']);
          return;
        }
        this.errorMsg.set('Profile could not be loaded.');
      },
    });
  }

  save(): void {
    const current = this.user();
    if (!current || !this.validate()) {
      return;
    }

    this.saving.set(true);
    this.errorMsg.set('');

    this.userProfileService.updateMe(this.toRequest(), current.version).subscribe({
      next: response => {
        this.applyUser(response.data);
        this.saving.set(false);
        this.toastService.success('Profile saved.');
      },
      error: error => {
        this.saving.set(false);
        if (error.status === 401) {
          this.router.navigate(['/login']);
          return;
        }
        if (error.status === 409) {
          this.errorMsg.set('Profile changed. Reset and try again.');
          return;
        }
        this.errorMsg.set('Profile could not be saved.');
      },
    });
  }

  private applyUser(user: CurrentUser): void {
    this.user.set(user);
    this.displayName = user.displayName ?? '';
    this.phone = user.phone ?? '';
    this.avatarUrl = user.avatarUrl ?? '';
  }

  private toRequest(): UpdateCurrentUserRequest {
    return {
      displayName: this.trimOrNull(this.displayName),
      phone: this.trimOrNull(this.phone),
      avatarUrl: this.trimOrNull(this.avatarUrl),
    };
  }

  private validate(): boolean {
    const displayName = this.displayName.trim();
    const phone = this.phone.trim();
    const avatarUrl = this.avatarUrl.trim();

    if (displayName.length > 200) {
      this.errorMsg.set('Display name is too long.');
      return false;
    }
    if (phone && !/^\+[1-9][0-9]{7,14}$/.test(phone)) {
      this.errorMsg.set('Phone must use E.164 format.');
      return false;
    }
    if (avatarUrl && !/^https?:\/\/.+/i.test(avatarUrl)) {
      this.errorMsg.set('Avatar URL must start with http:// or https://.');
      return false;
    }

    return true;
  }

  private trimOrNull(value: string): string | null {
    const trimmed = value.trim();
    return trimmed ? trimmed : null;
  }
}
