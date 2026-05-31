import { Routes } from '@angular/router';
import { DashboardPageComponent } from './pages/dashboard-page.component';
import { ExecutionPageComponent } from './pages/execution-page.component';
import { MonitoringPageComponent } from './pages/monitoring-page.component';
import { ReportsPageComponent } from './pages/reports-page.component';
import { AuthPageComponent } from './pages/auth-page.component';
import { EntrySignalsPageComponent } from './pages/entry-signals-page.component';
import { RejectedSignalsPageComponent } from './pages/rejected-signals-page.component';
import { StrategiesPageComponent } from './pages/strategies-page.component';
import { SettingsPageComponent } from './pages/settings-page.component';
import { UnderlyingConfigPageComponent } from './pages/underlying-config-page.component';
import { DiagnosticsPageComponent } from './pages/diagnostics-page.component';
import { TuningCapturePageComponent } from './pages/tuning-capture-page.component';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
  { path: 'dashboard', component: DashboardPageComponent },
  { path: 'strategies', component: StrategiesPageComponent },
  { path: 'settings', component: SettingsPageComponent },
  { path: 'tuning-capture', component: TuningCapturePageComponent },
  { path: 'index-config', component: UnderlyingConfigPageComponent },
  { path: 'execution', component: ExecutionPageComponent },
  { path: 'monitoring', component: MonitoringPageComponent },
  { path: 'entry-signals', component: EntrySignalsPageComponent },
  { path: 'rejected-signals', component: RejectedSignalsPageComponent },
  { path: 'reports', component: ReportsPageComponent },
  { path: 'auth', component: AuthPageComponent },
  { path: 'diagnostics', component: DiagnosticsPageComponent },
  { path: '**', redirectTo: 'dashboard' }
];
