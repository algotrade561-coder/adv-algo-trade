export type TradingMode = 'PAPER' | 'BACKTEST' | 'LIVE';
export type MarketDataMode = 'MOCK' | 'ZERODHA';
export type ExecutionMode = 'PAPER' | 'ZERODHA';
export type UnderlyingSymbol = 'NIFTY' | 'BANKNIFTY' | 'SENSEX' | 'FINNIFTY' | 'MIDCPNIFTY';
export type OptionType = 'CE' | 'PE';
export type Timeframe = 'ONE_MINUTE' | 'THREE_MINUTE' | 'FIVE_MINUTE' | 'FIFTEEN_MINUTE' | 'DAY';

export type HaltMode = 'NONE' | 'SOFT' | 'HARD';

export interface RuntimeStatus {
  running: boolean;
  killSwitch: boolean;
  haltMode: HaltMode;
  dailyApproved: boolean;
  extensionsUsedToday: number;
  dailyLossExtension: number;
  requestedMode: TradingMode;
  configuredMode: TradingMode;
  marketDataMode: MarketDataMode;
  executionMode: ExecutionMode;
  liveTradingEnabled: boolean;
  enabledUnderlyings: UnderlyingSymbol[];
  schedulerEnabled: boolean;
  webSocketConnected: boolean;
  updatedAt: string;
}

export interface ConfigParameter {
  path: string;
  value: unknown;
  description: string;
  usedBy: string;
  sensitive: boolean;
}

export interface ConfigResponse {
  configuration: Record<string, unknown>;
  parameters: ConfigParameter[];
  runtime: RuntimeStatus;
}

export interface HealthResponse {
  status?: string;
  broker?: unknown;
  database?: unknown;
  [key: string]: unknown;
}

export interface PnlSnapshot {
  realizedPnl?: number;
  unrealizedPnl?: number;
  totalPnl?: number;
  [key: string]: unknown;
}

export interface TradingStatus {
  entryAllowed: boolean;
  blockingReasons: string[];
  openTrades: number;
  openPaperTrades: number;
  pendingOrders: number;
  tradesToday: number;
  paperTradesToday: number;
  consecutiveLosses: number;
  dailyPnl: number;
  paperPnl: number;
  tradesThisHour: number;
  maxTradesPerHour: number;
  rollingWinRate: number;
  buyOrdersToday: number;
  entryWindowOpen: boolean;
  globalExitOverride: boolean;
  entrySignals: number;
  rejectedSignals: number;
  effectiveDailyLossLimit: number;
  lastScanAt?: string | null;
}

export interface JvmHealth {
  heapUsedMb: number;
  heapTotalMb: number;
  heapMaxMb: number;
  heapUsedPercent: number;
  threadCount: number;
  gcCollections: number;
  gcPauseMs: number;
  uptimeMs: number;
}

export interface MarketSnapshot {
  vix: number;
  pcr: number;
  nifty: number;
  banknifty: number;
  vixStatus: 'UNKNOWN' | 'LOW' | 'NORMAL' | 'ELEVATED' | 'HIGH';
  pcrBias: 'UNKNOWN' | 'BULLISH' | 'NEUTRAL' | 'BEARISH';
  circuitBreakerTriggered: boolean;
  eventDay: boolean;
  preEventDay: boolean;
  safeForLongPremium: boolean;
  longPremiumBlockReason: string | null;
}

export interface StrategyDecision {
  id?: number | string;
  timestamp?: string;
  underlying?: string;
  signalType?: string;
  strategyType?: string;
  underlyingPrice?: number;
  optionPrice?: number;
  optionOpenInterest?: number;
  lotSize?: number;
  lotPrice?: number;
  selectedInstrumentKey?: string;
  selectedStrike?: number;
  optionType?: string;
  confidenceScore?: number;
  ivRank?: number;
  bollingerBandwidth?: number;
  fastEma?: number;
  slowEma?: number;
  sellLegInstrumentKey?: string;
  sellLegStrike?: number;
  netPremium?: number;
  spreadStrikes?: number;
  reasons?: string;
  [key: string]: unknown;
}

export interface KiteLoginResponse {
  loginUrl: string;
  launched?: boolean;
  setupRequired?: boolean;
  message?: string;
  diagnostics: Record<string, unknown>;
}

export interface KiteSessionResponse {
  authenticated: boolean;
  userId: string;
  authenticatedAt: string;
  diagnostics: Record<string, unknown>;
}

export interface ReportArchiveResult {
  archived: boolean;
  archivePath: string;
  fileCount: number;
  archiveBytes: number;
  [key: string]: unknown;
}

export interface EntrySignalReplayTrade {
  decisionKey: string;
  instrument: string;
  optionType: OptionType;
  quantity: number;
  entryTime: string;
  entryPrice: number;
  exitTime: string;
  exitPrice: number;
  pnl: number;
  exitReason: string;
}

export interface EntrySignalReplaySummary {
  totalEvaluations: number;
  acceptedByFilters: number;
  blockedByBaseConditions: number;
  blockedByBreakoutConfirmation: number;
  blockedByOiSupport: number;
  blockedByHeadroom: number;
  sizingRejected: number;
  blockedByOpenTrade: number;
  executedTrades: number;
  winningTrades: number;
  losingTrades: number;
  totalPnl: number;
  trades: EntrySignalReplayTrade[];
}

export interface EntrySignalReplayResult {
  generatedAt: string;
  totalCapital: number;
  maxRiskPerTradePercent: number;
  stopLossPercent: number;
  targetPercent: number;
  trailingStopActivationPercent: number;
  trailingGapPercent: number;
  forcedExitTime: string;
  maxHoldMinutes: number;
  maxOpenTrades: number;
  htmlReportPath: string;
  summary: EntrySignalReplaySummary;
}

export interface BacktestRunRequest {
  underlying?: UnderlyingSymbol;
  timeframe?: Timeframe;
  optionType?: OptionType;
  from?: string;
  to?: string;
}

export interface DownloadDataRequest {
  instrumentToken?: string;
  from?: string;
  to?: string;
  timeframe?: Timeframe;
}

export interface DownloadUnderlyingDataRequest {
  underlying?: UnderlyingSymbol;
  from?: string;
  to?: string;
  timeframe?: Timeframe;
}

export interface DownloadOptionDataRequest {
  underlying?: UnderlyingSymbol;
  optionType?: OptionType;
  from?: string;
  to?: string;
  timeframe?: Timeframe;
  expiry?: string;
  strike?: number;
  underlyingPrice?: number;
}

export interface ReplayMonthRequest {
  underlying?: UnderlyingSymbol;
  optionType?: OptionType;
  optionTypes?: OptionType[];
  timeframe?: Timeframe;
  month?: string;
}

export interface SuiteRequest {
  underlying?: UnderlyingSymbol;
  to?: string;
  windows?: Array<{ name: string; from: string; to: string }>;
  optionTypes?: OptionType[];
  timeframes?: Timeframe[];
  variants?: Array<Record<string, unknown>>;
  expiry?: string;
  strike?: number;
  underlyingPrice?: number;
}

export interface AppendZerodhaOptionsRequest {
  underlying?: UnderlyingSymbol;
  from?: string;
  to?: string;
  timeframes?: Timeframe[];
  optionTypes?: OptionType[];
  expiry?: string;
  strike?: number;
  underlyingPrice?: number;
}

export type ApiRecord = Record<string, unknown>;

export interface PagedResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
  first: boolean;
  last: boolean;
}

export interface SignalFilters {
  strategyType?: string;
  underlying?: string;
  optionType?: string;
  mode?: string;
}
