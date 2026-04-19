export type TradingMode = 'PAPER' | 'BACKTEST' | 'LIVE';
export type MarketDataMode = 'MOCK' | 'ZERODHA';
export type ExecutionMode = 'PAPER' | 'ZERODHA';
export type UnderlyingSymbol = 'NIFTY' | 'BANKNIFTY';
export type OptionType = 'CE' | 'PE';
export type Timeframe = 'ONE_MINUTE' | 'THREE_MINUTE' | 'FIVE_MINUTE' | 'FIFTEEN_MINUTE' | 'DAY';

export interface RuntimeStatus {
  running: boolean;
  killSwitch: boolean;
  requestedMode: TradingMode;
  configuredMode: TradingMode;
  marketDataMode: MarketDataMode;
  executionMode: ExecutionMode;
  liveTradingEnabled: boolean;
  enabledUnderlyings: UnderlyingSymbol[];
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

export interface StrategyDecision {
  id?: number | string;
  timestamp?: string;
  underlying?: string;
  signalType?: string;
  underlyingPrice?: number;
  selectedInstrumentKey?: string;
  selectedStrike?: number;
  optionType?: string;
  confidenceScore?: number;
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
