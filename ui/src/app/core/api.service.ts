import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import {
  ApiRecord,
  ConfigResponse,
  EntrySignalReplayResult,
  ExecutionMode,
  HealthResponse,
  KiteLoginResponse,
  KiteSessionResponse,
  MarketDataMode,
  MarketSnapshot,
  PagedResponse,
  PnlSnapshot,
  ReportArchiveResult,
  RuntimeStatus,
  StrategyDecision,
  TradingMode,
  TradingStatus,
  UnderlyingSymbol
} from './models';

export interface StrategyDto {
  id: number; type: string; displayName: string; description: string;
  sellingStrategy: boolean; enabled: boolean; underlying: string;
  lots: number; stopLossPercent: number; targetPercent: number;
  maxHoldMinutes: number; spreadStrikes: number; otmStrikes: number;
  maxIvRankForBuying: number; minCombinedPremium: number;
  trailingStopActivationPercent: number; trailingGapPercent: number;
}

@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly base = '/advalgotrade';

  constructor(private readonly http: HttpClient) {}

  health(): Observable<HealthResponse> { return this.http.get<HealthResponse>(`${this.base}/health`); }
  brokerHealth(): Observable<HealthResponse> { return this.http.get<HealthResponse>(`${this.base}/health/broker`); }
  databaseHealth(): Observable<HealthResponse> { return this.http.get<HealthResponse>(`${this.base}/health/database`); }
  config(): Observable<ConfigResponse> { return this.http.get<ConfigResponse>(`${this.base}/config`); }
  tradingStatus(): Observable<TradingStatus> { return this.http.get<TradingStatus>(`${this.base}/trading/status`); }
  start(): Observable<RuntimeStatus> { return this.http.post<RuntimeStatus>(`${this.base}/start`, {}); }
  stop(): Observable<RuntimeStatus> { return this.http.post<RuntimeStatus>(`${this.base}/stop`, {}); }
  setKillSwitch(enabled: boolean): Observable<RuntimeStatus> { return this.http.post<RuntimeStatus>(`${this.base}/kill-switch`, { enabled }); }
  setScheduler(enabled: boolean): Observable<RuntimeStatus> { return this.http.post<RuntimeStatus>(`${this.base}/scheduler`, { enabled }); }
  softHalt(reason: string): Observable<RuntimeStatus> { return this.http.post<RuntimeStatus>(`${this.base}/halt/soft`, { reason }); }
  resumeFromHalt(): Observable<RuntimeStatus> { return this.http.post<RuntimeStatus>(`${this.base}/halt/resume`, {}); }
  approveToday(): Observable<RuntimeStatus> { return this.http.post<RuntimeStatus>(`${this.base}/daily/approve`, {}); }
  revokeApproval(): Observable<RuntimeStatus> { return this.http.post<RuntimeStatus>(`${this.base}/daily/revoke`, {}); }
  extendDailyLimit(): Observable<RuntimeStatus> { return this.http.post<RuntimeStatus>(`${this.base}/daily/extend-limit`, {}); }
  reconnectWebSocket(): Observable<RuntimeStatus> { return this.http.post<RuntimeStatus>(`${this.base}/websocket/reconnect`, {}); }
  disconnectWebSocket(): Observable<RuntimeStatus> { return this.http.post<RuntimeStatus>(`${this.base}/websocket/disconnect`, {}); }
  placeOrder(order: { instrumentKey: string; side: string; orderType: string; productType: string; quantity: number; limitPrice?: number; tag?: string }): Observable<ApiRecord> {
    return this.http.post<ApiRecord>(`${this.base}/orders/place`, order);
  }
  setMode(mode: TradingMode): Observable<RuntimeStatus> { return this.http.post<RuntimeStatus>(`${this.base}/mode`, { mode }); }
  routing(): Observable<RuntimeStatus> { return this.http.get<RuntimeStatus>(`${this.base}/routing`); }
  setRouting(marketDataMode: MarketDataMode, executionMode: ExecutionMode): Observable<RuntimeStatus> {
    return this.http.post<RuntimeStatus>(`${this.base}/routing`, { marketDataMode, executionMode });
  }
  scanUnderlyings(): Observable<RuntimeStatus> { return this.http.get<RuntimeStatus>(`${this.base}/scan/underlyings`); }
  setScanUnderlying(underlying: UnderlyingSymbol, enabled: boolean): Observable<RuntimeStatus> {
    return this.http.post<RuntimeStatus>(`${this.base}/scan/underlyings/${underlying}`, { enabled });
  }
  positions(): Observable<ApiRecord[]> { return this.http.get<ApiRecord[]>(`${this.base}/positions`); }
  orders(): Observable<ApiRecord[]> { return this.http.get<ApiRecord[]>(`${this.base}/orders`); }
  trades(): Observable<ApiRecord[]> { return this.http.get<ApiRecord[]>(`${this.base}/trades`); }
  pnl(): Observable<PnlSnapshot> { return this.http.get<PnlSnapshot>(`${this.base}/pnl`); }
  market(): Observable<MarketSnapshot> { return this.http.get<MarketSnapshot>(`${this.base}/market`); }
  latestSignal(): Observable<StrategyDecision | null> { return this.http.get<StrategyDecision | null>(`${this.base}/signals/latest`); }
  recentSignals(): Observable<StrategyDecision[]> { return this.http.get<StrategyDecision[]>(`${this.base}/signals/recent`); }
  tradeJournalCsv(): Observable<string> {
    return this.http.get(`${this.base}/trades/journal.csv`, { responseType: 'text', headers: new HttpHeaders({ Accept: 'text/csv' }) });
  }
  archiveEntrySignals(): Observable<ReportArchiveResult> { return this.http.post<ReportArchiveResult>(`${this.base}/reports/entry-signals/archive`, {}); }
  kiteLogin(): Observable<KiteLoginResponse> { return this.http.get<KiteLoginResponse>(`${this.base}/auth/kite/login`); }
  kiteSession(): Observable<KiteSessionResponse> { return this.http.get<KiteSessionResponse>(`${this.base}/auth/kite/session`); }
  runBacktest(request: Record<string, unknown>): Observable<ApiRecord> { return this.http.post<ApiRecord>(`${this.base}/backtest/run`, clean(request)); }
  runAllBacktest(request: Record<string, unknown>): Observable<ApiRecord> { return this.http.post<ApiRecord>(`${this.base}/backtest/run-all`, clean(request)); }
  runSuite(request: Record<string, unknown>): Observable<ApiRecord> { return this.http.post<ApiRecord>(`${this.base}/backtest/run-suite`, clean(request)); }
  appendZerodhaOptions(request: Record<string, unknown>): Observable<ApiRecord> { return this.http.post<ApiRecord>(`${this.base}/data-maintenance/append-zerodha-options`, clean(request)); }

  // ── Signal endpoints ────────────────────────────────────────────────────
  entrySignals(): Observable<StrategyDecision[]> { return this.http.get<StrategyDecision[]>(`${this.base}/signals/entries`); }
  entrySignalsPaged(page: number, size: number): Observable<PagedResponse<StrategyDecision>> {
    return this.http.get<PagedResponse<StrategyDecision>>(`${this.base}/signals/entries/paged?page=${page}&size=${size}`);
  }
  rejectedSignals(): Observable<StrategyDecision[]> { return this.http.get<StrategyDecision[]>(`${this.base}/signals/rejected`); }
  rejectedSignalsPaged(page: number, size: number): Observable<PagedResponse<StrategyDecision>> {
    return this.http.get<PagedResponse<StrategyDecision>>(`${this.base}/signals/rejected/paged?page=${page}&size=${size}`);
  }
  replayEntrySignals(): Observable<EntrySignalReplayResult> { return this.http.post<EntrySignalReplayResult>(`${this.base}/reports/entry-signals/replay`, {}); }

  // ── Strategy management ───────────────────────────────────────────────────
  getStrategies(): Observable<StrategyDto[]> { return this.http.get<StrategyDto[]>(`${this.base}/strategies`); }
  enableStrategy(type: string): Observable<any> { return this.http.post(`${this.base}/strategies/${type}/enable`, {}); }
  disableStrategy(type: string): Observable<any> { return this.http.post(`${this.base}/strategies/${type}/disable`, {}); }
  updateStrategy(type: string, patch: Partial<StrategyDto>): Observable<any> { return this.http.put(`${this.base}/strategies/${type}`, patch); }

  // ── Signal endpoints ────────────────────────────────────────────────────
  signalsByStrategy(strategyType: string): Observable<StrategyDecision[]> {
    return this.http.get<StrategyDecision[]>(`${this.base}/signals/by-strategy/${strategyType}`);
  }
  signalSummary(): Observable<Array<{ strategyType: string; count: number }>> {
    return this.http.get<Array<{ strategyType: string; count: number }>>(`${this.base}/signals/summary`);
  }
}

function clean<T extends Record<string, unknown>>(value: T): Partial<T> {
  return Object.fromEntries(
    Object.entries(value).filter(([, v]) => {
      if (Array.isArray(v)) return v.length > 0;
      return v !== undefined && v !== null && v !== '';
    })
  ) as Partial<T>;
}
