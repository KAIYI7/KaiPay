import React from 'react';
import { NavLink } from 'react-router-dom';
import { LayoutDashboard, CreditCard, FlaskConical, Layers, AlertOctagon, BookOpen } from 'lucide-react';

export const Sidebar: React.FC = () => {
  const paymentCoreItems = [
    { label: 'Dashboard', path: '/dashboard', icon: LayoutDashboard },
    { label: 'Payments', path: '/payments', icon: CreditCard },
    { label: 'Testing Sandbox', path: '/sandbox', icon: FlaskConical },
    { label: 'Outbox Stream', path: '/events/outbox', icon: Layers },
    { label: 'DLT Explorer', path: '/events/dlt', icon: AlertOctagon },
  ];

  const financialCoreItems = [
    { label: 'Ledger & Accounts', path: '/ledger', icon: BookOpen },
  ];

  return (
    <aside className="w-64 bg-slate-900/95 border-r border-slate-800 flex flex-col justify-between p-4 shrink-0 min-h-[calc(100vh-4rem)]">
      <div className="space-y-6">
        {/* Payment Core Navigation */}
        <div>
          <div className="text-[11px] font-semibold text-slate-500 uppercase tracking-wider px-3 mb-2">
            Payment Core (Phase 3)
          </div>
          <nav className="space-y-1">
            {paymentCoreItems.map((item) => {
              const Icon = item.icon;
              return (
                <NavLink
                  key={item.path}
                  to={item.path}
                  className={({ isActive }) =>
                    `flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium transition-colors ${
                      isActive
                        ? 'bg-emerald-600/15 text-emerald-400 border border-emerald-500/20'
                        : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/60'
                    }`
                  }
                >
                  <Icon className="w-4 h-4" />
                  <span>{item.label}</span>
                </NavLink>
              );
            })}
          </nav>
        </div>

        {/* Financial Core (Phase 4) Navigation */}
        <div>
          <div className="text-[11px] font-semibold text-slate-500 uppercase tracking-wider px-3 mb-2">
            Financial Core (Phase 4)
          </div>
          <nav className="space-y-1">
            {financialCoreItems.map((item) => {
              const Icon = item.icon;
              return (
                <NavLink
                  key={item.path}
                  to={item.path}
                  className={({ isActive }) =>
                    `flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium transition-colors ${
                      isActive
                        ? 'bg-emerald-600/15 text-emerald-400 border border-emerald-500/20'
                        : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/60'
                    }`
                  }
                >
                  <Icon className="w-4 h-4" />
                  <span>{item.label}</span>
                </NavLink>
              );
            })}
          </nav>
        </div>
      </div>

      <div className="p-3 bg-slate-800/40 border border-slate-800 rounded-xl">
        <div className="text-xs font-semibold text-slate-300">Backend Port: 28080</div>
        <div className="text-[11px] text-slate-500 mt-0.5">PostgreSQL: 25432 (Isolated)</div>
      </div>
    </aside>
  );
};
