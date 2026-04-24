import { Routes } from '@angular/router';
import { DashboardPageComponent } from './pages/dashboard-page.component';
import { ExecutionPageComponent } from './pages/execution-page.component';
import { ConfigPageComponent } from './pages/config-page.component';
import { MonitoringPageComponent } from './pages/monitoring-page.component';
import { ReportsPageComponent } from './pages/reports-page.component';
import { AuthPageComponent } from './pages/auth-page.component';
import { BacktestsPageComponent } from './pages/backtests-page.component';
import { DataMaintenancePageComponent } from './pages/data-maintenance-page.component';
import { EntrySignalsPageComponent } from './pages/entry-signals-page.component';
import { RejectedSignalsPageComponent } from './pages/rejected-signals-page.component';
import { StrategiesPageComponent } from './pages/strategies-page.component';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
  { path: 'dashboard', component: DashboardPageComponent },
  { path: 'strategies', component: StrategiesPageComponent },
  { path: 'execution', component: ExecutionPageComponent },
  { path: 'config', component: ConfigPageComponent },
  { path: 'monitoring', component: MonitoringPageComponent },
  { path: 'entry-signals', component: EntrySignalsPageComponent },
  { path: 'rejected-signals', component: RejectedSignalsPageComponent },
  { path: 'reports', component: ReportsPageComponent },
  { path: 'auth', component: AuthPageComponent },
  { path: 'backtests', component: BacktestsPageComponent },
  { path: 'data-maintenance', component: DataMaintenancePageComponent },
  { path: '**', redirectTo: 'dashboard' }
];
