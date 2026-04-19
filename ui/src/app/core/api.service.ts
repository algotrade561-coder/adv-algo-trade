import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import {
  ApiRecord,
  ConfigResponse,
  ExecutionMode,
  HealthResponse,
  KiteLoginResponse,
  KiteSessionResponse,
  MarketDataMode,
  PnlSnapshot,
  ReportArchiveResult,
  RuntimeStatus,
  StrategyDecision,
  TradingMode,
  UnderlyingSymbol
} from './models';

@Injectable({ providedIn: 'root' })
export class ApiService {
  constructor(private readonly http: HttpClient) {
  }

  health(): Observable<HealthResponse> {
    return this.http.get<HealthResponse>('/health');
  }

  brokerHealth(): Observable<HealthResponse> {
    return this.http.get<HealthResponse>('/health/broker');
  }

  databaseHealth(): Observable<HealthResponse> {
    return this.http.get<HealthResponse>('/health/database');
  }

  config(): Observable<ConfigResponse> {
    return this.http.get<ConfigResponse>('/config');
  }

  start(): Observable<RuntimeStatus> {
    return this.http.post<RuntimeStatus>('/start', {});
  }

  stop(): Observable<RuntimeStatus> {
    return this.http.post<RuntimeStatus>('/stop', {});
  }

  setKillSwitch(enabled: boolean): Observable<RuntimeStatus> {
    return this.http.post<RuntimeStatus>('/kill-switch', { enabled });
  }

  setMode(mode: TradingMode): Observable<RuntimeStatus> {
    return this.http.post<RuntimeStatus>('/mode', { mode });
  }

  routing(): Observable<RuntimeStatus> {
    return this.http.get<RuntimeStatus>('/routing');
  }

  setRouting(marketDataMode: MarketDataMode, executionMode: ExecutionMode): Observable<RuntimeStatus> {
    return this.http.post<RuntimeStatus>('/routing', { marketDataMode, executionMode });
  }

  scanUnderlyings(): Observable<RuntimeStatus> {
    return this.http.get<RuntimeStatus>('/scan/underlyings');
  }

  setScanUnderlying(underlying: UnderlyingSymbol, enabled: boolean): Observable<RuntimeStatus> {
    return this.http.post<RuntimeStatus>(`/scan/underlyings/${underlying}`, { enabled });
  }

  positions(): Observable<ApiRecord[]> {
    return this.http.get<ApiRecord[]>('/positions');
  }

  orders(): Observable<ApiRecord[]> {
    return this.http.get<ApiRecord[]>('/orders');
  }

  trades(): Observable<ApiRecord[]> {
    return this.http.get<ApiRecord[]>('/trades');
  }

  pnl(): Observable<PnlSnapshot> {
    return this.http.get<PnlSnapshot>('/pnl');
  }

  latestSignal(): Observable<StrategyDecision | null> {
    return this.http.get<StrategyDecision | null>('/signals/latest');
  }

  recentSignals(): Observable<StrategyDecision[]> {
    return this.http.get<StrategyDecision[]>('/signals/recent');
  }

  tradeJournalCsv(): Observable<string> {
    return this.http.get('/trades/journal.csv', {
      responseType: 'text',
      headers: new HttpHeaders({ Accept: 'text/csv' })
    });
  }

  archiveEntrySignals(): Observable<ReportArchiveResult> {
    return this.http.post<ReportArchiveResult>('/reports/entry-signals/archive', {});
  }

  kiteLogin(): Observable<KiteLoginResponse> {
    return this.http.get<KiteLoginResponse>('/auth/kite/login');
  }

  kiteSession(): Observable<KiteSessionResponse> {
    return this.http.get<KiteSessionResponse>('/auth/kite/session');
  }

  runBacktest(request: Record<string, unknown>): Observable<ApiRecord> {
    return this.http.post<ApiRecord>('/backtest/run', clean(request));
  }

  downloadData(request: Record<string, unknown>): Observable<ApiRecord> {
    return this.http.post<ApiRecord>('/backtest/download-data', clean(request));
  }

  downloadOptionData(request: Record<string, unknown>): Observable<ApiRecord> {
    return this.http.post<ApiRecord>('/backtest/download-option-data', clean(request));
  }

  runSuite(request: Record<string, unknown>): Observable<ApiRecord> {
    return this.http.post<ApiRecord>('/backtest/run-suite', clean(request));
  }

  analyzeVariants(request: Record<string, unknown>): Observable<ApiRecord> {
    return this.http.post<ApiRecord>('/backtest/analyze-variants', clean(request));
  }

  analyzeQuick(request: Record<string, unknown>): Observable<ApiRecord> {
    return this.http.post<ApiRecord>('/backtest/analyze-quick', clean(request));
  }

  analyzeFocusedValidation(request: Record<string, unknown>): Observable<ApiRecord> {
    return this.http.post<ApiRecord>('/backtest/analyze-focused-validation', clean(request));
  }

  backtestResult(id: string): Observable<ApiRecord> {
    return this.http.get<ApiRecord>(`/backtest/results/${encodeURIComponent(id)}`);
  }

  appendZerodhaOptions(request: Record<string, unknown>): Observable<ApiRecord> {
    return this.http.post<ApiRecord>('/data-maintenance/append-zerodha-options', clean(request));
  }
}

function clean<T extends Record<string, unknown>>(value: T): Partial<T> {
  return Object.fromEntries(
    Object.entries(value).filter(([, fieldValue]) => {
      if (Array.isArray(fieldValue)) {
        return fieldValue.length > 0;
      }
      return fieldValue !== undefined && fieldValue !== null && fieldValue !== '';
    })
  ) as Partial<T>;
}
