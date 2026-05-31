import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import {
  ApiRecord,
  ConfigResponse,
  EntrySignalReplayResult,
  SignalTuningRunResult,
  ExecutionMode,
  HealthResponse,
  JvmHealth,
  KiteLoginResponse,
  KiteSessionResponse,
  MarketDataMode,
  MarketSnapshot,
  OilPriceSnapshot,
  PagedResponse,
  PnlSnapshot,
  DailyBundleSummary,
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

// ── Tuning capture (Phase 1) ─────────────────────────────────────────────
export interface TuningCaptureDto {
  strategy: string;
  displayName: string;
  captureEnabled: boolean;
  captureEvaluations: boolean;
  captureSignals: boolean;
  captureExecutions: boolean;
  captureExits: boolean;
  captureForward: boolean;
  captureShadow: boolean;
  episodeWindowSec: number;
  notes: string | null;
}

export interface TuningCaptureUpdate {
  captureEnabled: boolean;
  captureEvaluations: boolean;
  captureSignals: boolean;
  captureExecutions: boolean;
  captureExits: boolean;
  captureForward: boolean;
  captureShadow: boolean;
  episodeWindowSec: number;
  notes: string | null;
  reason: string;
}

export interface TuningCaptureAuditDto {
  id: number;
  changedAt: string;
  changedBy: string;
  fieldName: string;
  oldValue: string | null;
  newValue: string | null;
  reason: string | null;
}

export interface UnderlyingConfigDto {
  underlying: string;
  enabled: boolean;
  displayName: string;
  hasWeeklyExpiry: boolean;
  expiryPreference: string;
  maxDteForBuying: number;
  breakoutBufferPercent: number;
  minBreakoutPoints: number;
  volumeSpikeMode: string;
  entryCutoffTime: string | null;
  middayChopStart: string | null;
  middayChopEnd: string | null;
  normalizeScoreForNoVolume: boolean;
  maxEntryPremium: number;
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
  minEnvironmentScore: number;
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
  globalExitOverride: boolean;
  manageSyncedTrades: boolean;
  // Risk
  totalCapital: number;
  maxRiskPerTradePercent: number;
  maxDailyLossPercent: number;
  maxTradesPerDay: number;
  maxConsecutiveLosses: number;
  maxOpenTrades: number;
  cooldownMinutes: number;
  directionFlipCooldownMinutes: number;
  maxOpenPositionsPerStrategy: number;
  dailyProfitTarget: number;
  maxLotsPerTrade: number;
  mlVirtualTradeThreshold: number;
  // Execution tuning
  limitOrderCancelMinutes: number;
  failSafeSquareoffTime: string;
  maxPendingOrders: number;
  ivCollapseExitThresholdPercent: number;
  ivCollapseMaxProfitPercent: number;
  maxEntriesPerScanPerUnderlying: number;
  maxEntriesPerScan: number;
}

/** Live OI Momentum / V3 operator controls (DB-backed, no restart). */
export interface OiMomentumRuntimeConfigDto {
  enabled: boolean;
  paperTrading: boolean;
  v3Enabled: boolean;
  v3ShadowMode: boolean;
  antiPyramidEnabled: boolean;
  antiPyramidCooldownMinutes: number;
  expiryOtmCutoffEnabled: boolean;
  expiryOtmCutoffTime: string;
  dailyLossLimitRupees: number;
  dailyLossMultiplierOfAvgLoser: number;
  consecutiveLossHaltCount: number;
  breakEvenTriggerPercent: number;
  maxTradesPerDay: number;
  // ── Legacy enhancements (29 May 2026 — data-validated; see OI_MOMENTUM_EMPIRICAL_REPLAY_RESULTS.md) ──
  legacyTimeOfDayModeEnabled: boolean;
  case0Enabled: boolean;
  case0ShadowMode: boolean;
  case0OpScoreThreshold: number;
  case0CoilMaxPct: number;
  case0PcrSlopeMinAbs: number;
  case4WatchlistBonusEnabled: boolean;
  // R3 — Adaptive CASE 0 for low-VIX (29 May 2026 — replay 82% 60m win)
  case0LowVixEnabled: boolean;
  case0LowVixVixThreshold: number;
  case0LowVixOpScoreThreshold: number;
  case0LowVixCoilMaxPct: number;
  // R2 — Range-edge fade (29 May 2026 — replay 54% 30m win, ~14/day, addresses range-bound gap)
  rangeEdgeFadeEnabled: boolean;
  rangeEdgeFadeRangeMaxPct: number;
  rangeEdgeFadeEdgePct: number;
  rangeEdgeFadeOiBuildMin: number;
  // Theta-decay gate
  thetaDecayCheckEnabled: boolean;
  thetaDecayMaxCostPct: number;
  // P4 instrumentation (data-gathering week)
  recordEveryReject: boolean;
  rejectSampleIntervalSeconds: number;
  matrixRejectSampleIntervalSeconds: number;
  summaryRejectTopN: number;
  updatedAt: string;
  updatedBy: string;
  updatedReason: string;
}

export interface OiMomentumSettingsUpdate extends Partial<OiMomentumRuntimeConfigDto> {
  reason: string;
}

export interface OiMomentumIndexHaltDto {
  haltedForDay: boolean;
  consecutiveLosses: number;
  consecutiveLossPauseThreshold: number;
  consecutiveLossHaltThreshold: number;
  inLossPause: boolean;
  slCooldownRemainingSeconds: number;
  cooldownAfterSlSeconds: number;
  tradesToday: number;
  maxTradesPerDay: number;
  atTradeCap: boolean;
  dailyPnl: number;
  totalLossesToday: number;
  totalLossesPnl: number;
  blocked: boolean;
}

export interface OiMomentumHaltStatusDto {
  strategyEnabled: boolean;
  indices: Record<string, OiMomentumIndexHaltDto>;
  note?: string;
}

export interface OiMomentumHaltActionRequest {
  indices?: string[];
  clearHaltedForDay?: boolean;
  clearConsecutiveLosses?: boolean;
  clearSlCooldown?: boolean;
  resetTradesToday?: boolean;
  reason: string;
}

export interface OiMomentumHaltActionResponse {
  result: Record<string, unknown>;
  haltStatus: OiMomentumHaltStatusDto;
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
  positionHealth(): Observable<ApiRecord[]> { return this.http.get<ApiRecord[]>(`${this.base}/positions/health`); }
  orders(): Observable<ApiRecord[]> { return this.http.get<ApiRecord[]>(`${this.base}/orders`); }
  trades(): Observable<ApiRecord[]> { return this.http.get<ApiRecord[]>(`${this.base}/trades`); }
  pnl(): Observable<PnlSnapshot> { return this.http.get<PnlSnapshot>(`${this.base}/pnl`); }
  market(): Observable<MarketSnapshot> { return this.http.get<MarketSnapshot>(`${this.base}/market`); }
  oilPrice(): Observable<OilPriceSnapshot> { return this.http.get<OilPriceSnapshot>(`${this.base}/api/oil-price/snapshot`); }
  performance(): Observable<any> { return this.http.get<any>(`${this.base}/performance`); }
  oiHeatmap(index: string, strikes = 15): Observable<any> { return this.http.get<any>(`${this.base}/analytics/heatmap/oi/${index}?strikes=${strikes}`); }
  greeksDashboard(): Observable<any> { return this.http.get<any>(`${this.base}/analytics/greeks`); }
  executionTimeline(period = 'TODAY'): Observable<any> { return this.http.get<any>(`${this.base}/analytics/timeline?period=${period}`); }
  auditExport(period = 'TODAY'): Observable<any> { return this.http.get<any>(`${this.base}/analytics/audit?period=${period}`); }
  botActivity(): Observable<any> { return this.http.get<any>(`${this.base}/analytics/bot-activity`); }
  diagnosticsHealth(): Observable<any> { return this.http.get<any>(`${this.base}/diagnostics/health`); }
  diagnosticsAudit(tradeId: string): Observable<any> { return this.http.get<any>(`${this.base}/diagnostics/audit/${tradeId}`); }
  diagnosticsSearchInstrument(key: string, period = 'TODAY'): Observable<any> { return this.http.get<any>(`${this.base}/diagnostics/search/instrument?key=${key}&period=${period}`); }
  diagnosticsSearchStrategy(type: string, period = 'TODAY'): Observable<any> { return this.http.get<any>(`${this.base}/diagnostics/search/strategy?type=${type}&period=${period}`); }
  diagnosticsFailures(period = 'TODAY'): Observable<any> { return this.http.get<any>(`${this.base}/diagnostics/failures?period=${period}`); }
  diagnosticsLookup(period = 'TODAY'): Observable<any> { return this.http.get<any>(`${this.base}/diagnostics/lookup?period=${period}`); }
  diagnosticsRecentErrors(limit = 50): Observable<any> { return this.http.get<any>(`${this.base}/diagnostics/errors/recent?limit=${limit}`); }
  diagnosticsSchedulers(): Observable<any[]> { return this.http.get<any[]>(`${this.base}/diagnostics/schedulers`); }
  diagnosticsToggleScheduler(name: string, enabled: boolean): Observable<any> { return this.http.post<any>(`${this.base}/diagnostics/schedulers/${name}/toggle`, { enabled }); }
  diagnosticsTriggerScheduler(name: string): Observable<any> { return this.http.post<any>(`${this.base}/diagnostics/schedulers/${name}/trigger`, {}); }
  latestSignal(): Observable<StrategyDecision | null> { return this.http.get<StrategyDecision | null>(`${this.base}/signals/latest`); }
  recentSignals(): Observable<StrategyDecision[]> { return this.http.get<StrategyDecision[]>(`${this.base}/signals/recent`); }
  tradeJournalCsv(): Observable<string> {
    return this.http.get(`${this.base}/trades/journal.csv`, { responseType: 'text', headers: new HttpHeaders({ Accept: 'text/csv' }) });
  }
  archiveEntrySignals(): Observable<ReportArchiveResult> { return this.http.post<ReportArchiveResult>(`${this.base}/reports/entry-signals/archive`, {}); }
  todayAnalysisDownloadSummary(): Observable<DailyBundleSummary> {
    return this.http.get<DailyBundleSummary>(`${this.base}/reports/download/today/summary`);
  }
  downloadTodaySignalsZip(): Observable<Blob> {
    return this.http.get(`${this.base}/reports/download/today/signals`, { responseType: 'blob' });
  }
  downloadTodayLogsZip(): Observable<Blob> {
    return this.http.get(`${this.base}/reports/download/today/logs`, { responseType: 'blob' });
  }
  downloadTodayChainSnapshotsZip(): Observable<Blob> {
    return this.http.get(`${this.base}/reports/download/today/chain-snapshots`, { responseType: 'blob' });
  }
  downloadTodayAnalysisPackZip(): Observable<Blob> {
    return this.http.get(`${this.base}/reports/download/today/all`, { responseType: 'blob' });
  }
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
  generateSignalTuningReport(): Observable<SignalTuningRunResult> {
    return this.http.post<SignalTuningRunResult>(`${this.base}/reports/signal-tuning/generate`, {});
  }
  signalTuningReportUrl(path?: string): string {
    const q = path ? `?path=${encodeURIComponent(path)}` : '';
    return `${this.base}/reports/signal-tuning/report${q}`;
  }

  // ── Tuning capture toggle (Phase 1) ───────────────────────────────────────
  // /tuning/capture/* — see SIGNAL_CAPTURE_TUNING_REDESIGN.md
  listTuningCapture(): Observable<TuningCaptureDto[]> {
    return this.http.get<TuningCaptureDto[]>(`${this.base}/tuning/capture/strategies`);
  }
  getTuningCapture(strategy: string): Observable<TuningCaptureDto> {
    return this.http.get<TuningCaptureDto>(`${this.base}/tuning/capture/${strategy}`);
  }
  updateTuningCapture(strategy: string, body: TuningCaptureUpdate): Observable<TuningCaptureDto> {
    return this.http.put<TuningCaptureDto>(`${this.base}/tuning/capture/${strategy}`, body);
  }
  getTuningCaptureAudit(strategy: string, limit: number = 50): Observable<TuningCaptureAuditDto[]> {
    return this.http.get<TuningCaptureAuditDto[]>(`${this.base}/tuning/capture/${strategy}/audit?limit=${limit}`);
  }

  // ── Strategy management ───────────────────────────────────────────────────
  getStrategies(): Observable<StrategyDto[]> { return this.http.get<StrategyDto[]>(`${this.base}/strategies`); }
  getStrategyTypes(): Observable<Array<{type: string; displayName: string}>> { return this.http.get<Array<{type: string; displayName: string}>>(`${this.base}/strategies/types`); }
  getStrategiesByUnderlying(underlying: string): Observable<StrategyDto[]> { return this.http.get<StrategyDto[]>(`${this.base}/strategies/by-underlying/${underlying}`); }
  enableStrategy(type: string, underlying: string): Observable<any> { return this.http.post(`${this.base}/strategies/${type}/enable?underlying=${underlying}`, {}); }
  disableStrategy(type: string, underlying: string): Observable<any> { return this.http.post(`${this.base}/strategies/${type}/disable?underlying=${underlying}`, {}); }
  updateStrategy(type: string, underlying: string, patch: Partial<StrategyDto>): Observable<any> { return this.http.put(`${this.base}/strategies/${type}?underlying=${underlying}`, patch); }

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

  // ── OI Momentum V3 runtime (operator, no restart) ───────────────────────
  getOiMomentumSettings(): Observable<OiMomentumRuntimeConfigDto> {
    return this.http.get<OiMomentumRuntimeConfigDto>(`${this.base}/oi-momentum/settings`);
  }
  updateOiMomentumSettings(body: OiMomentumSettingsUpdate): Observable<OiMomentumRuntimeConfigDto> {
    return this.http.post<OiMomentumRuntimeConfigDto>(`${this.base}/oi-momentum/settings`, body);
  }
  killOiMomentum(reason?: string): Observable<OiMomentumRuntimeConfigDto> {
    return this.http.post<OiMomentumRuntimeConfigDto>(`${this.base}/oi-momentum/settings/kill`, { reason: reason ?? '' });
  }
  getOiMomentumHalts(): Observable<OiMomentumHaltStatusDto> {
    return this.http.get<OiMomentumHaltStatusDto>(`${this.base}/oi-momentum/settings/halts`);
  }
  resumeOiMomentumHalts(body: OiMomentumHaltActionRequest): Observable<OiMomentumHaltActionResponse> {
    return this.http.post<OiMomentumHaltActionResponse>(`${this.base}/oi-momentum/settings/resume`, body);
  }
  extendOiMomentumHalts(body: OiMomentumHaltActionRequest): Observable<OiMomentumHaltActionResponse> {
    return this.http.post<OiMomentumHaltActionResponse>(`${this.base}/oi-momentum/settings/halts/extend`, body);
  }

  // ── Underlying config ───────────────────────────────────────────────────
  getUnderlyingConfigs(): Observable<UnderlyingConfigDto[]> { return this.http.get<UnderlyingConfigDto[]>(`${this.base}/underlying-config`); }
  getUnderlyingConfig(underlying: string): Observable<UnderlyingConfigDto> { return this.http.get<UnderlyingConfigDto>(`${this.base}/underlying-config/${underlying}`); }
  updateUnderlyingConfig(underlying: string, config: UnderlyingConfigDto): Observable<UnderlyingConfigDto> { return this.http.put<UnderlyingConfigDto>(`${this.base}/underlying-config/${underlying}`, config); }

  // ── Tuning reports (Phase 6) ─────────────────────────────────────────
  submitTuningReport(from: string, to: string, strategies = '*', force = false, forceReason?: string): Observable<{ jobId: string }> {
    const params = new URLSearchParams({ from, to, strategies, force: String(force) });
    if (forceReason) params.set('forceReason', forceReason);
    return this.http.post<{ jobId: string }>(`${this.base}/reports/tuning/jobs?${params}`, {});
  }
  listTuningReportJobs(limit = 20): Observable<Record<string, unknown>[]> {
    return this.http.get<Record<string, unknown>[]>(`${this.base}/reports/tuning/jobs?limit=${limit}`);
  }
  getTuningReportJob(jobId: string): Observable<Record<string, unknown>> {
    return this.http.get<Record<string, unknown>>(`${this.base}/reports/tuning/jobs/${jobId}`);
  }
  getTuningReportHtml(jobId: string): Observable<string> {
    return this.http.get(`${this.base}/reports/tuning/jobs/${jobId}/html`, { responseType: 'text' });
  }
  getTuningStrategyToday(name: string): Observable<Record<string, unknown>> {
    return this.http.get<Record<string, unknown>>(`${this.base}/reports/tuning/strategy/${name}/today`);
  }
  exploreTuningBuckets(strategy: string, from: string, to: string): Observable<Record<string, unknown>> {
    return this.http.get<Record<string, unknown>>(
      `${this.base}/reports/tuning/explore/buckets?strategy=${encodeURIComponent(strategy)}&from=${from}&to=${to}`);
  }
  getTuningHealth(): Observable<Record<string, any>> {
    return this.http.get<Record<string, any>>(`${this.base}/tuning/health`);
  }

  // ── Auth ─────────────────────────────────────────────────────────────
  checkAuth(): Observable<any> { return this.http.get<any>(`${this.base}/auth/user`); }
}

function clean<T extends Record<string, unknown>>(value: T): Partial<T> {
  return Object.fromEntries(
    Object.entries(value).filter(([, v]) => {
      if (Array.isArray(v)) return v.length > 0;
      return v !== undefined && v !== null && v !== '';
    })
  ) as Partial<T>;
}
