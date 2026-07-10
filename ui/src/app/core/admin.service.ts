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

export interface IpAllocation {
  privateIp?: string;
  publicIp?: string;
  eniId?: string;
  status?: 'PENDING' | 'OS_CONFIGURED' | 'ASSOCIATED' | 'ACTIVE' | 'FAILED' | 'RELEASING' | 'RELEASED' | 'NONE';
  whitelistedWithBroker?: boolean;
  manuallyManaged?: boolean;
  lastError?: string;
}

export interface IpCapacity {
  activeAllocations: number;
  eipMax: number;
  eipRemaining: number;
  eniSecondaryCapacity: number;
  eniSecondaryRemaining?: number | null;
}

export interface ProvisionIpRequest {
  privateIp?: string;
  publicIp?: string;
  eniId?: string;
  eipAllocationId?: string;
  eipAssociationId?: string;
  instanceId?: string;
}

// ── Phase 3: AWS / Source-IP reconciliation dashboard ──
export type EipDisposition = 'NECESSARY' | 'REMOVABLE' | 'EXTERNAL' | 'TABLE_ONLY' | 'PROTECTED';

export interface EipRow {
  allocationId?: string | null;
  publicIp?: string | null;
  privateIp?: string | null;
  associationId?: string | null;
  eniId?: string | null;
  instanceId?: string | null;
  userId?: number | null;
  email?: string | null;
  userEnabled: boolean;
  userDeleted: boolean;
  status?: IpAllocation['status'] | null;
  manuallyManaged: boolean;
  disposition: EipDisposition;
  note?: string;
}

export interface AwsIpCapacity {
  eipMax: number;
  eipUsed: number;
  eipRemaining: number;
  activeAllocations: number;
  managedEips: number;
  externalEips: number;
}

export interface ReconciliationReport {
  generatedAt: string;
  automationEnabled: boolean;
  ourEni?: string | null;
  ourInstance?: string | null;
  mappings: EipRow[];
  removableCount: number;
  removableMonthlyCostUsd: number;
  driftCount: number;
  failedOrReleasing: number;
  capacity: AwsIpCapacity;
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
  ipAllocation?: IpAllocation | null;
  /** Instance primary IP for the primary/system account (no per-user allocation row). */
  systemPublicIp?: string | null;
  systemPrivateIp?: string | null;
  /** Assigned risk profile (set by superuser; drives the user's resolved risk/sizing). */
  riskProfile?: string;
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
  /** SUPERUSER — assign a risk profile to a user (drives their resolved risk/sizing). */
  assignUserRiskProfile(userId: number, riskProfile: string): Observable<unknown> {
    return this.http.put(`${this.base}/trading-settings/admin/${userId}/profile`, { riskProfile });
  }

  // ── Admin: per-user source IP (Phase 1 = track-only) ──
  provisionSourceIp(id: number, req: ProvisionIpRequest): Observable<IpAllocation> {
    return this.http.post<IpAllocation>(`${this.base}/admin/app-users/${id}/source-ip:provision`, req);
  }
  releaseSourceIp(id: number): Observable<IpAllocation> {
    return this.http.post<IpAllocation>(`${this.base}/admin/app-users/${id}/source-ip:release`, {});
  }
  /** Live AWS auto-allocate a fresh Elastic IP for an existing user (e.g. after release). SUPERUSER only. */
  allocateSourceIp(id: number): Observable<IpAllocation> {
    return this.http.post<IpAllocation>(`${this.base}/admin/app-users/${id}/source-ip:allocate`, {});
  }
  setSourceIpWhitelisted(id: number, whitelistedWithBroker: boolean): Observable<IpAllocation> {
    return this.http.patch<IpAllocation>(`${this.base}/admin/app-users/${id}/source-ip`, { whitelistedWithBroker });
  }
  sourceIpCapacity(): Observable<IpCapacity> {
    return this.http.get<IpCapacity>(`${this.base}/admin/app-users/source-ip/capacity`);
  }

  // ── Admin: AWS / Source-IP reconciliation dashboard (Phase 3, SUPERUSER) ──
  awsIpReconcile(): Observable<ReconciliationReport> {
    return this.http.get<ReconciliationReport>(`${this.base}/admin/aws-ip/reconcile`);
  }
  awsIpReleaseEip(allocationId: string): Observable<unknown> {
    return this.http.post(`${this.base}/admin/aws-ip/release`, { allocationId });
  }
  awsIpCapacity(): Observable<AwsIpCapacity> {
    return this.http.get<AwsIpCapacity>(`${this.base}/admin/aws-ip/capacity`);
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
