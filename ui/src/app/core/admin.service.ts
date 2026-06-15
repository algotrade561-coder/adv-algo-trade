import { HttpClient } from '@angular/common/http';
import { Injectable, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';

export interface CurrentUser {
  authenticated: boolean;
  email?: string;
  name?: string;
  role?: 'USER' | 'ADMIN' | 'SUPERUSER';
  userId?: number | null;
  picture?: string;
}

export interface AdminUser {
  id: number;
  email: string;
  name?: string;
  role: 'USER' | 'ADMIN' | 'SUPERUSER';
  enabled: boolean;
  primaryAccount?: boolean;
  createdAt?: string;
  lastLoginAt?: string;
}

export interface CreateUserRequest {
  email: string;
  name?: string;
  role: 'USER' | 'ADMIN' | 'SUPERUSER';
  enabled?: boolean;
}

export interface UpdateUserRequest {
  name?: string;
  role?: 'USER' | 'ADMIN' | 'SUPERUSER';
  enabled?: boolean;
}

export interface MyBrokerConfig {
  userId: number;
  email: string;
  configured: boolean;
  apiKey?: string;
  apiSecretMasked?: boolean;
  accessTokenPresent?: boolean;
  tokenValid?: boolean;
  tokenVerifyMessage?: string;
  tokenVerifiedAt?: string;
  telegramChatId?: string;
  telegramLinked?: boolean;
  webhookConfigured?: boolean;
  tradingEnabled?: boolean;
  totalCapital?: number;
  dailyMaxLoss?: number;
  maxOpenPositions?: number;
  maxLotsPerTrade?: number;
  primaryAccount?: boolean;
  sourceIp?: string;
  tokenExpiresAt?: string;
  updatedAt?: string;
}

export interface SaveBrokerRequest {
  apiKey?: string;
  apiSecret?: string;
  webhookUrl?: string;
  totalCapital?: number;
  dailyMaxLoss?: number;
  maxOpenPositions?: number;
  maxLotsPerTrade?: number;
  tradingEnabled?: boolean;
  sourceIp?: string;
}

export interface TelegramLinkChallenge {
  otp: string;
  deepLink?: string;
  botUsername?: string;
  expiresAt: string;
}

export interface TelegramLinkStatus {
  linked: boolean;
  chatId?: string;
  pendingOtp?: string;
  botUsername?: string;
}

export interface MyTradingState {
  userId: number;
  email: string;
  running: boolean;
  killSwitch: boolean;
  haltMode: 'NONE' | 'SOFT' | 'HARD';
  dailyApproved: boolean;
  entryAllowed: boolean;
  exitAllowed: boolean;
  schedulerEnabled: boolean;
  extensionsUsedToday: number;
  dailyLossExtension: number;
  lastScanAt?: string;
  brokerConfigured: boolean;
  brokerAuthenticated: boolean;
  tradingEnabled: boolean;
  sourceIp?: string;
  tokenExpiresAt?: string;
  primaryAccount: boolean;
}

@Injectable({ providedIn: 'root' })
export class AdminService {
  private readonly base = '/advalgotrade';

  /** Reactive cache of the current logged-in user (loaded on app bootstrap). */
  readonly currentUser = signal<CurrentUser | null>(null);

  constructor(private readonly http: HttpClient) {}

  loadCurrentUser(): Observable<CurrentUser> {
    return this.http.get<CurrentUser>(`${this.base}/auth/user`).pipe(
      tap(u => this.currentUser.set(u))
    );
  }

  isAdmin(): boolean {
    const u = this.currentUser();
    return u?.role === 'ADMIN' || u?.role === 'SUPERUSER';
  }

  isSuperUser(): boolean {
    return this.currentUser()?.role === 'SUPERUSER';
  }

  // ── Admin: user management ──
  listUsers(): Observable<AdminUser[]> {
    return this.http.get<AdminUser[]>(`${this.base}/admin/app-users`);
  }
  createUser(req: CreateUserRequest): Observable<AdminUser> {
    return this.http.post<AdminUser>(`${this.base}/admin/app-users`, req);
  }
  updateUser(id: number, req: UpdateUserRequest): Observable<AdminUser> {
    return this.http.put<AdminUser>(`${this.base}/admin/app-users/${id}`, req);
  }
  deleteUser(id: number): Observable<unknown> {
    return this.http.delete(`${this.base}/admin/app-users/${id}`);
  }

  // ── Self-service: broker config ──
  getMyBroker(): Observable<MyBrokerConfig> {
    return this.http.get<MyBrokerConfig>(`${this.base}/me/broker`);
  }
  saveMyBroker(req: SaveBrokerRequest): Observable<MyBrokerConfig> {
    return this.http.put<MyBrokerConfig>(`${this.base}/me/broker`, req);
  }
  testTelegram(): Observable<{ sent: boolean; status?: number; error?: string }> {
    return this.http.post<{ sent: boolean; status?: number; error?: string }>(
      `${this.base}/me/broker/test-telegram`, {});
  }
  startTelegramLink(): Observable<TelegramLinkChallenge> {
    return this.http.post<TelegramLinkChallenge>(`${this.base}/me/broker/telegram/start-link`, {});
  }
  telegramLinkStatus(): Observable<TelegramLinkStatus> {
    return this.http.get<TelegramLinkStatus>(`${this.base}/me/broker/telegram/link-status`);
  }
  unlinkTelegram(): Observable<{ unlinked: boolean }> {
    return this.http.post<{ unlinked: boolean }>(`${this.base}/me/broker/telegram/unlink`, {});
  }
  setPrimaryAccount(userId: number, primary: boolean): Observable<{ userId: number; primaryAccount: boolean }> {
    return this.http.post<{ userId: number; primaryAccount: boolean }>(
      `${this.base}/admin/app-users/${userId}/primary-account`, { primary });
  }
  kiteLoginUrl(): Observable<{ loginUrl: string }> {
    return this.http.get<{ loginUrl: string }>(`${this.base}/me/broker/kite/login-url`);
  }

  // ── Self-service: per-user trading state ──
  getMyTradingState(): Observable<MyTradingState> {
    return this.http.get<MyTradingState>(`${this.base}/me/trading`);
  }
  myTradingStart(): Observable<MyTradingState> {
    return this.http.post<MyTradingState>(`${this.base}/me/trading/start`, {});
  }
  myTradingStop(): Observable<MyTradingState> {
    return this.http.post<MyTradingState>(`${this.base}/me/trading/stop`, {});
  }
  myTradingKillSwitch(enabled: boolean): Observable<MyTradingState> {
    return this.http.post<MyTradingState>(`${this.base}/me/trading/kill-switch`, { enabled });
  }
  myTradingHalt(mode: 'SOFT' | 'HARD', reason?: string): Observable<MyTradingState> {
    return this.http.post<MyTradingState>(`${this.base}/me/trading/halt`, { mode, reason: reason ?? '' });
  }
  myTradingResume(): Observable<MyTradingState> {
    return this.http.post<MyTradingState>(`${this.base}/me/trading/resume`, {});
  }
  myTradingApprove(): Observable<MyTradingState> {
    return this.http.post<MyTradingState>(`${this.base}/me/trading/approve`, {});
  }
  myTradingRevokeApproval(): Observable<MyTradingState> {
    return this.http.post<MyTradingState>(`${this.base}/me/trading/revoke-approval`, {});
  }
  myTradingExtendLimit(): Observable<MyTradingState> {
    return this.http.post<MyTradingState>(`${this.base}/me/trading/extend-limit`, {});
  }
}
