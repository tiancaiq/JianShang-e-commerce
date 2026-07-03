import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { UserProfileService } from '../../core/services/user-profile.service';
import { ToastService } from '../../core/services/toast.service';
import { AuthService } from '../../core/services/auth.service';
import { CurrentUser, UpdateCurrentUserRequest } from '../../core/models/user.model';
import { StatusPillComponent } from '../../shared/components/ui/status-pill.component';
import { environment } from '../../../environments/environment';

@Component({
  selector: 'app-profile',
  standalone: true,
  imports: [FormsModule, StatusPillComponent],
  template: `
    <section class="profile-page">
      <header class="profile-header">
        <div>
          <p class="eyebrow">Marketplace account</p>
          <h1>Profile</h1>
          <p class="profile-subtitle">{{ user()?.email || 'Signed in account' }}</p>
        </div>
        <app-ui-status-pill>{{ user()?.status || 'LOADING' }}</app-ui-status-pill>
      </header>

      <form class="profile-form" (ngSubmit)="save()">
        <section class="avatar-panel" aria-label="Avatar image">
          <div class="avatar-preview">
            @if (avatarPreviewUrl()) {
              <img [src]="avatarPreviewUrl()" alt="Current avatar preview" />
            } @else {
              <span>{{ initials() }}</span>
            }
          </div>

          <div class="avatar-controls">
            <strong>Avatar image</strong>
            <p>Upload a PNG, JPEG, or WebP image up to 5 MB.</p>
            <input
              #avatarInput
              type="file"
              accept="image/png,image/jpeg,image/webp"
              (change)="selectAvatar($event)"
              [disabled]="loading() || saving() || avatarSaving()"
              hidden
            />
            <div class="avatar-actions">
              <button type="button" class="secondary-btn" (click)="avatarInput.click()" [disabled]="loading() || saving() || avatarSaving()">
                Choose image
              </button>
              <button type="button" class="primary-btn" (click)="uploadAvatar()" [disabled]="!selectedAvatarFile || loading() || saving() || avatarSaving()">
                {{ avatarSaving() && selectedAvatarFile ? 'Uploading' : 'Upload' }}
              </button>
              <button type="button" class="secondary-btn danger-btn" (click)="removeAvatar()" [disabled]="!user()?.avatarUrl || loading() || saving() || avatarSaving()">
                Remove
              </button>
            </div>
          </div>
        </section>

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

        </div>

        @if (errorMsg()) {
          <div class="error-message">{{ errorMsg() }}</div>
        }

        <div class="actions">
          <button type="button" class="secondary-btn" (click)="load()" [disabled]="loading() || saving()">
            Reset
          </button>
          <button type="submit" class="primary-btn" [disabled]="loading() || saving() || avatarSaving()">
            {{ saving() ? 'Saving' : 'Save' }}
          </button>
        </div>
      </form>
    </section>
  `,
  styles: [`
    :host {
      display: block;
      --profile-surface: var(--color-bg-secondary);
      --profile-field: var(--color-bg-tertiary);
      --profile-border: var(--color-border);
      --profile-text: var(--color-text-primary);
      --profile-muted: var(--color-text-secondary);
      --profile-subtle: var(--color-text-muted);
      --profile-accent: var(--color-accent);
      --profile-accent-muted: var(--color-accent-muted);
      --profile-primary-bg: var(--color-accent);
      --profile-primary-text: #0c0c0e;
      --profile-shadow: none;
    }

    :host-context(.marketplace-shell) {
      --profile-surface: rgba(255, 255, 255, 0.92);
      --profile-field: #fff8fc;
      --profile-border: var(--market-line);
      --profile-text: var(--market-ink);
      --profile-muted: var(--market-muted);
      --profile-subtle: var(--market-muted);
      --profile-accent: var(--market-accent-dark);
      --profile-accent-muted: rgba(244, 114, 182, 0.16);
      --profile-primary-bg: linear-gradient(135deg, #ff85bd, #8b6fe8);
      --profile-primary-text: #fff;
      --profile-shadow: 0 14px 34px rgba(143, 92, 144, 0.1);
    }

    .profile-page {
      display: flex;
      flex-direction: column;
      gap: 1.5rem;
      max-width: 860px;
      margin: 0 auto;
    }

    .profile-header {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 1rem;
      padding: 1.25rem;
      border: 1px solid var(--profile-border);
      border-radius: var(--radius-lg);
      background: var(--profile-surface);
      box-shadow: var(--profile-shadow);
    }

    .eyebrow {
      margin: 0 0 0.35rem;
      color: var(--profile-accent);
      font-size: 0.75rem;
      font-weight: 850;
      letter-spacing: 0.08em;
      text-transform: uppercase;
    }

    .profile-header h1 {
      font-size: 1.75rem;
      margin-bottom: 0.25rem;
      color: var(--profile-text);
    }

    .profile-subtitle {
      margin: 0;
      color: var(--profile-subtle);
      font-size: 0.875rem;
      font-weight: 650;
    }

    .profile-form {
      background: var(--profile-surface);
      border: 1px solid var(--profile-border);
      border-radius: var(--radius-lg);
      padding: 1.25rem;
      display: flex;
      flex-direction: column;
      gap: 1.25rem;
      box-shadow: var(--profile-shadow);
    }

    .avatar-panel {
      display: grid;
      grid-template-columns: 112px minmax(0, 1fr);
      gap: 1rem;
      align-items: center;
      padding: 1rem;
      border: 1px solid var(--profile-border);
      border-radius: var(--radius-md);
      background: var(--profile-field);
    }

    .avatar-preview {
      width: 96px;
      height: 96px;
      display: grid;
      place-items: center;
      overflow: hidden;
      border-radius: 999px;
      border: 3px solid rgba(244, 114, 182, 0.5);
      background: linear-gradient(135deg, #ffc1de, #a88df1);
      color: #fff;
      font-size: 1.75rem;
      font-weight: 900;
    }

    .avatar-preview img {
      width: 100%;
      height: 100%;
      object-fit: cover;
    }

    .avatar-controls {
      display: grid;
      gap: 0.45rem;
      min-width: 0;
    }

    .avatar-controls strong {
      color: var(--profile-text);
      font-size: 1rem;
    }

    .avatar-controls p {
      margin: 0;
      color: var(--profile-muted);
      font-size: 0.86rem;
      line-height: 1.4;
    }

    .avatar-actions {
      display: flex;
      flex-wrap: wrap;
      gap: 0.6rem;
      margin-top: 0.25rem;
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
      color: var(--profile-muted);
    }

    .field input {
      width: 100%;
      min-height: 40px;
      padding: 0.625rem 0.75rem;
      background: var(--profile-field);
      border: 1px solid var(--profile-border);
      border-radius: var(--radius-md);
      color: var(--profile-text);
      font-size: 0.875rem;
      outline: none;
    }

    .field input:focus {
      border-color: var(--profile-accent);
      box-shadow: 0 0 0 3px var(--profile-accent-muted);
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
      background: var(--profile-primary-bg);
      color: var(--profile-primary-text);
    }

    .secondary-btn {
      background: var(--profile-field);
      color: var(--profile-muted);
      border-color: var(--profile-border);
    }

    .danger-btn {
      color: var(--color-danger);
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

      .avatar-panel {
        grid-template-columns: 1fr;
        justify-items: center;
        text-align: center;
      }

      .avatar-actions {
        justify-content: center;
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
  private authService = inject(AuthService);
  private router = inject(Router);

  user = signal<CurrentUser | null>(null);
  loading = signal(false);
  saving = signal(false);
  avatarSaving = signal(false);
  errorMsg = signal('');

  displayName = '';
  phone = '';
  avatarUrl = '';
  selectedAvatarFile: File | null = null;
  private selectedAvatarPreviewUrl = '';

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.errorMsg.set('');

    this.userProfileService.getMe().subscribe({
      next: user => {
        this.applyUser(user);
        this.loading.set(false);
      },
      error: error => {
        this.loading.set(false);
        if (error.status === 401) {
          this.redirectToLogin();
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
      next: user => {
        this.applyUser(user);
        this.saving.set(false);
        this.toastService.success('Profile saved.');
      },
      error: error => {
        this.saving.set(false);
        if (error.status === 401) {
          this.redirectToLogin();
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

  selectAvatar(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0] || null;
    input.value = '';
    if (!file) {
      return;
    }
    if (!this.validateAvatarFile(file)) {
      return;
    }
    this.clearSelectedAvatarPreview();
    this.selectedAvatarFile = file;
    this.selectedAvatarPreviewUrl = URL.createObjectURL(file);
    this.errorMsg.set('');
  }

  uploadAvatar(): void {
    const current = this.user();
    if (!current || !this.selectedAvatarFile || !this.validateAvatarFile(this.selectedAvatarFile)) {
      return;
    }

    this.avatarSaving.set(true);
    this.errorMsg.set('');
    this.userProfileService.uploadAvatar(this.selectedAvatarFile, current.version).subscribe({
      next: user => {
        this.applyUser(user);
        this.clearSelectedAvatarPreview();
        this.avatarSaving.set(false);
        this.toastService.success('Avatar updated.');
      },
      error: error => {
        this.avatarSaving.set(false);
        if (error.status === 401) {
          this.redirectToLogin();
          return;
        }
        if (error.status === 409) {
          this.errorMsg.set('Profile changed. Reset and try again.');
          return;
        }
        this.errorMsg.set('Avatar could not be uploaded.');
      },
    });
  }

  removeAvatar(): void {
    const current = this.user();
    if (!current || !current.avatarUrl) {
      return;
    }

    this.avatarSaving.set(true);
    this.errorMsg.set('');
    this.userProfileService.deleteAvatar(current.version).subscribe({
      next: user => {
        this.applyUser(user);
        this.clearSelectedAvatarPreview();
        this.avatarSaving.set(false);
        this.toastService.success('Avatar removed.');
      },
      error: error => {
        this.avatarSaving.set(false);
        if (error.status === 401) {
          this.redirectToLogin();
          return;
        }
        if (error.status === 409) {
          this.errorMsg.set('Profile changed. Reset and try again.');
          return;
        }
        this.errorMsg.set('Avatar could not be removed.');
      },
    });
  }

  avatarPreviewUrl(): string {
    return this.selectedAvatarPreviewUrl || this.displayAvatarUrl(this.user()?.avatarUrl || '');
  }

  initials(): string {
    const source = this.displayName.trim() || this.user()?.email?.split('@')[0] || 'M';
    const parts = source.trim().split(/\s+/).filter(Boolean);
    const first = parts[0]?.[0] || 'M';
    const second = parts.length > 1 ? parts[1]?.[0] : '';
    return `${first}${second}`.toUpperCase();
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

    if (displayName.length > 200) {
      this.errorMsg.set('Display name is too long.');
      return false;
    }
    if (phone && !/^\+[1-9][0-9]{7,14}$/.test(phone)) {
      this.errorMsg.set('Phone must use E.164 format.');
      return false;
    }

    return true;
  }

  private validateAvatarFile(file: File): boolean {
    if (!['image/png', 'image/jpeg', 'image/webp'].includes(file.type)) {
      this.errorMsg.set('Avatar image must be a PNG, JPEG, or WebP file.');
      return false;
    }
    if (file.size > 5 * 1024 * 1024) {
      this.errorMsg.set('Avatar image must be 5 MB or smaller.');
      return false;
    }
    return true;
  }

  private clearSelectedAvatarPreview(): void {
    if (this.selectedAvatarPreviewUrl) {
      URL.revokeObjectURL(this.selectedAvatarPreviewUrl);
    }
    this.selectedAvatarPreviewUrl = '';
    this.selectedAvatarFile = null;
  }

  private trimOrNull(value: string): string | null {
    const trimmed = value.trim();
    return trimmed ? trimmed : null;
  }

  private displayAvatarUrl(value: string): string {
    const trimmed = value.trim();
    if (trimmed.startsWith('/api/')) {
      return `${environment.apiGatewayUrl}${trimmed}`;
    }
    return trimmed;
  }

  private redirectToLogin(): void {
    this.authService.clearUser();
    this.router.navigate(['/login'], {
      queryParams: {
        client: 'marketplace',
        returnUrl: this.router.url,
      },
    });
  }
}
