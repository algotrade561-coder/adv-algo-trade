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
import { AiInsightsPageComponent } from './pages/ai-insights-page.component';
import { MlScorecardPageComponent } from './pages/ml-scorecard-page.component';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
  { path: 'dashboard', component: DashboardPageComponent },
  { path: 'strategies', component: StrategiesPageComponent },
  { path: 'settings', component: SettingsPageComponent },
  { path: 'execution', component: ExecutionPageComponent },
  { path: 'monitoring', component: MonitoringPageComponent },
  { path: 'entry-signals', component: EntrySignalsPageComponent },
  { path: 'rejected-signals', component: RejectedSignalsPageComponent },
  { path: 'reports', component: ReportsPageComponent },
  { path: 'auth', component: AuthPageComponent },
  { path: 'ai-insights', component: AiInsightsPageComponent },
  { path: 'ml-scorecard', component: MlScorecardPageComponent },
  { path: '**', redirectTo: 'dashboard' }
];
