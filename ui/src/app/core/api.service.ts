import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import {
  ApiRecord,
  ConfigResponse,
  EntrySignalReplayResult,
  ExecutionMode,
  HealthResponse,
  JvmHealth,
  KiteLoginResponse,
  KiteSessionResponse,
  MarketDataMode,
  MarketSnapshot,
  PagedResponse,
  PnlSnapshot,
  ReportArchiveResult,
  RuntimeStatus,
  SignalFilters,
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
  scanTimeframe: string; candleTimeframe: string; trendTimeframe: string;
  paperTrading: boolean;
  itmDepth: number; minimumMove: number; minimumStrengthGap: number; minimumVolume: number;
  squareoffHour: number; squareoffMinute: number;
}

export interface GlobalConfigDto {
  // Entry
  timeframe: string;
  trendTimeframe: string;
  enabledOptionTypes: string;
  vwapFilterEnabled: boolean;
  trendFilterEnabled: boolean;
  volumeSpikeMultiplier: number;
  breakoutBufferPercent: number;
  breakoutLookback: number;
  volumeLookback: number;
  bullishImbalanceThreshold: number;
  bearishImbalanceThreshold: number;
  minLiquidityVolume: number;
  maxIvPercent: number;
  minSignalScorePercent: number;
  ceOiSupportRequired: boolean;
  peOiSupportRequired: boolean;
  ceOiDivergenceFilterEnabled: boolean;
  peOiDivergenceFilterEnabled: boolean;
  oiDivergenceMultiplier: number;
  oiDivergenceMinChange: number;
  ceBreakoutConfirmationCandles: number;
  peBreakoutConfirmationCandles: number;
  entryStartTime: string;
  entryCutoffTime: string;
  allowFirstMinutesEntry: boolean;
  noEntryFirstMinutes: number;
  rsiFilterEnabled: boolean;
  rsiPeriod: number;
  rsiCeBuyThreshold: number;
  rsiPeSellThreshold: number;
  // Exit
  stopLossPercent: number;
  targetPercent: number;
  trailingStopActivationPercent: number;
  trailingGapPercent: number;
  forcedExitTime: string;
  partialProfitBookingEnabled: boolean;
  maxHoldMinutes: number;
  vwapExitEnabled: boolean;
  // Risk
  totalCapital: number;
  maxRiskPerTradePercent: number;
  maxDailyLossPercent: number;
  maxTradesPerDay: number;
  maxOrdersPerDay: number;
  maxConsecutiveLosses: number;
  maxOpenTrades: number;
  sameInstrumentReentryMinPriceMovePercent: number;
  cooldownMinutes: number;
  dailyProfitTarget: number;
  maxLotsPerTrade: number;
}

@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly base = '/advalgotrade';

  constructor(private readonly http: HttpClient) {}

  health(): Observable<HealthResponse> { return this.http.get<HealthResponse>(`${this.base}/health`); }
  brokerHealth(): Observable<HealthResponse> { return this.http.get<HealthResponse>(`${this.base}/health/broker`); }
  databaseHealth(): Observable<HealthResponse> { return this.http.get<HealthResponse>(`${this.base}/health/database`); }
  jvmHealth(): Observable<JvmHealth> { return this.http.get<JvmHealth>(`${this.base}/health/jvm`); }
  config(): Observable<ConfigResponse> { return this.http.get<ConfigResponse>(`${this.base}/config`); }
  tradingStatus(): Observable<TradingStatus> { return this.http.get<TradingStatus>(`${this.base}/trading/status`); }
  start(): Observable<RuntimeStatus> { return this.http.post<RuntimeStatus>(`${this.base}/start`, {}); }
  stop(): Observable<RuntimeStatus> { return this.http.post<RuntimeStatus>(`${this.base}/stop`, {}); }
  setKillSwitch(enabled: boolean): Observable<RuntimeStatus> { return this.http.post<RuntimeStatus>(`${this.base}/kill-switch`, { enabled }); }
  setScheduler(enabled: boolean): Observable<RuntimeStatus> { return this.http.post<RuntimeStatus>(`${this.base}/scheduler`, { enabled }); }
  softHalt(reason: string): Observable<RuntimeStatus> { return this.http.post<RuntimeStatus>(`${this.base}/halt/soft`, { reason }); }
  hardHalt(reason: string): Observable<RuntimeStatus> { return this.http.post<RuntimeStatus>(`${this.base}/halt/hard`, { reason }); }
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
  entrySignalsPaged(page: number, size: number, period?: string, filters?: SignalFilters): Observable<PagedResponse<StrategyDecision>> {
    let q = `page=${page}&size=${size}`;
    if (period && period !== 'ALL') q += `&period=${period}`;
    if (filters?.strategyType && filters.strategyType !== 'ALL') q += `&strategyType=${filters.strategyType}`;
    if (filters?.underlying && filters.underlying !== 'ALL') q += `&underlying=${filters.underlying}`;
    if (filters?.optionType && filters.optionType !== 'ALL') q += `&optionType=${filters.optionType}`;
    if (filters?.mode && filters.mode !== 'ALL') q += `&mode=${filters.mode}`;
    return this.http.get<PagedResponse<StrategyDecision>>(`${this.base}/signals/entries/paged?${q}`);
  }
  rejectedSignals(): Observable<StrategyDecision[]> { return this.http.get<StrategyDecision[]>(`${this.base}/signals/rejected`); }
  rejectedSignalsPaged(page: number, size: number, period?: string, filters?: SignalFilters): Observable<PagedResponse<StrategyDecision>> {
    let q = `page=${page}&size=${size}`;
    if (period && period !== 'ALL') q += `&period=${period}`;
    if (filters?.strategyType && filters.strategyType !== 'ALL') q += `&strategyType=${filters.strategyType}`;
    if (filters?.underlying && filters.underlying !== 'ALL') q += `&underlying=${filters.underlying}`;
    if (filters?.optionType && filters.optionType !== 'ALL') q += `&optionType=${filters.optionType}`;
    if (filters?.mode && filters.mode !== 'ALL') q += `&mode=${filters.mode}`;
    return this.http.get<PagedResponse<StrategyDecision>>(`${this.base}/signals/rejected/paged?${q}`);
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
  filterFunnel(period = 'TODAY'): Observable<Array<{ filter: string; count: number }>> {
    return this.http.get<Array<{ filter: string; count: number }>>(`${this.base}/signals/filter-funnel?period=${period}`);
  }
  strategyScorecard(period = 'LAST30'): Observable<Array<{ strategyType: string; totalEntries: number; filled: number; rejected: number; fillRate: number; avgIvRank: number; avgSpread: number; ivRankSource: string }>> {
    return this.http.get<Array<{ strategyType: string; totalEntries: number; filled: number; rejected: number; fillRate: number; avgIvRank: number; avgSpread: number; ivRankSource: string }>>(`${this.base}/signals/strategy-scorecard?period=${period}`);
  }

  // ── Global config ─────────────────────────────────────────────────────
  getGlobalConfig(): Observable<GlobalConfigDto> { return this.http.get<GlobalConfigDto>(`${this.base}/global-config`); }
  updateGlobalConfig(config: GlobalConfigDto): Observable<GlobalConfigDto> { return this.http.put<GlobalConfigDto>(`${this.base}/global-config`, config); }
  resetGlobalConfig(): Observable<GlobalConfigDto> { return this.http.post<GlobalConfigDto>(`${this.base}/global-config/reset`, {}); }

  // ── AI Insights ───────────────────────────────────────────────────────
  getAiRecommendations(period = 'today'): Observable<import('../pages/ai-insights-page.component').AiRecommendationDto[]> {
    return this.http.get<import('../pages/ai-insights-page.component').AiRecommendationDto[]>(`${this.base}/ai/recommendations?period=${period}`);
  }
  runAiAnalysis(): Observable<import('../pages/ai-insights-page.component').AiRecommendationDto> {
    return this.http.post<import('../pages/ai-insights-page.component').AiRecommendationDto>(`${this.base}/ai/recommendations/run`, {});
  }
  runBacktestTune(underlying = 'NIFTY'): Observable<import('../pages/ai-insights-page.component').AiRecommendationDto> {
    return this.http.post<import('../pages/ai-insights-page.component').AiRecommendationDto>(`${this.base}/ai/backtest/sync-and-tune?underlying=${underlying}`, {});
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
