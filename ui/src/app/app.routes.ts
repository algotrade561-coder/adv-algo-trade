import { Routes } from '@angular/router';
import { DashboardPageComponent } from './pages/dashboard-page.component';
import { ExecutionPageComponent } from './pages/execution-page.component';
import { MonitoringPageComponent } from './pages/monitoring-page.component';
import { MarketMemoryPageComponent } from './pages/market-memory-page.component';
import { ReportsPageComponent } from './pages/reports-page.component';
import { AuthPageComponent } from './pages/auth-page.component';
import { EntrySignalsPageComponent } from './pages/entry-signals-page.component';
import { RejectedSignalsPageComponent } from './pages/rejected-signals-page.component';
import { StrategiesPageComponent } from './pages/strategies-page.component';
import { SettingsPageComponent } from './pages/settings-page.component';
import { adminGuard, superUserGuard } from './core/admin.guard';
import { UnderlyingConfigPageComponent } from './pages/underlying-config-page.component';
import { DiagnosticsPageComponent } from './pages/diagnostics-page.component';
import { TuningCapturePageComponent } from './pages/tuning-capture-page.component';
import { TuningReportPageComponent } from './pages/tuning-report-page.component';
import { TuningStrategyPageComponent } from './pages/tuning-strategy-page.component';
import { TuningExplorePageComponent } from './pages/tuning-explore-page.component';
import { AdminUsersPageComponent } from './pages/admin-users-page.component';
import { MyBrokerPageComponent } from './pages/my-broker-page.component';
import { MyTradingPageComponent } from './pages/my-trading-page.component';
import { AwsIpPageComponent } from './pages/aws-ip-page.component';
import { MicrostructurePageComponent } from './pages/microstructure-page.component';
import { OiMomentumConfigPageComponent } from './pages/oi-momentum-config-page.component';
import { TradingSettingsPageComponent } from './pages/trading-settings-page.component';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
  { path: 'dashboard', component: DashboardPageComponent },
  { path: 'strategies', component: StrategiesPageComponent },
  { path: 'settings', component: TradingSettingsPageComponent },
  { path: 'settings/advanced', component: SettingsPageComponent, canActivate: [superUserGuard] },
  { path: 'oi-momentum-config', component: OiMomentumConfigPageComponent },
  { path: 'tuning-capture', component: TuningCapturePageComponent, canActivate: [superUserGuard] },
  { path: 'tuning/dashboard', redirectTo: 'reports' },
  { path: 'tuning/reports/:jobId', component: TuningReportPageComponent },
  { path: 'tuning/strategy/:name', component: TuningStrategyPageComponent },
  { path: 'tuning/explore', component: TuningExplorePageComponent },
  { path: 'index-config', component: UnderlyingConfigPageComponent },
  { path: 'execution', component: ExecutionPageComponent },
  { path: 'monitoring', component: MonitoringPageComponent },
  { path: 'market-memory', component: MarketMemoryPageComponent },
  { path: 'entry-signals', component: EntrySignalsPageComponent },
  { path: 'rejected-signals', component: RejectedSignalsPageComponent },
  { path: 'reports', component: ReportsPageComponent },
  { path: 'auth', component: AuthPageComponent },
  { path: 'my-broker', component: MyBrokerPageComponent },
  { path: 'my-trading', component: MyTradingPageComponent },
  { path: 'admin/users', component: AdminUsersPageComponent, canActivate: [adminGuard] },
  { path: 'admin/aws-ip', component: AwsIpPageComponent, canActivate: [superUserGuard] },
  { path: 'diagnostics', component: DiagnosticsPageComponent },
  { path: 'microstructure', component: MicrostructurePageComponent },
  { path: '**', redirectTo: 'dashboard' }
];
