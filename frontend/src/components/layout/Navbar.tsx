import React from 'react';
import { useMerchant } from '../../context/MerchantContext';
import { Building2, ShieldCheck, ChevronDown, Check } from 'lucide-react';

export const Navbar: React.FC = () => {
  const { activeMerchant, setActiveMerchant, availableMerchants } = useMerchant();
  const [dropdownOpen, setDropdownOpen] = React.useState(false);

  return (
    <header className="h-16 bg-slate-900/90 border-b border-slate-800 px-6 flex items-center justify-between sticky top-0 z-30 backdrop-blur-md">
      <div className="flex items-center gap-4">
        <div className="flex items-center gap-2">
          <div className="w-8 h-8 rounded-lg bg-gradient-to-tr from-emerald-600 to-teal-400 flex items-center justify-center font-bold text-white shadow-lg shadow-emerald-900/20">
            K
          </div>
          <div>
            <span className="font-bold text-slate-100 text-lg tracking-tight">KaiPay</span>
            <span className="text-[10px] text-emerald-400 ml-2 font-mono uppercase bg-emerald-950/60 border border-emerald-800/50 px-1.5 py-0.5 rounded">
              Phase 1 Core
            </span>
          </div>
        </div>

        <div className="hidden lg:flex items-center gap-2 bg-slate-800/60 border border-slate-700/50 px-3 py-1 rounded-full text-xs text-slate-300">
          <ShieldCheck className="w-3.5 h-3.5 text-amber-400" />
          <span>Simulation Mode: Multi-Tenant Test Harness</span>
        </div>
      </div>

      <div className="flex items-center gap-3">
        {/* Merchant Context Switcher */}
        <div className="relative">
          <button
            type="button"
            onClick={() => setDropdownOpen(!dropdownOpen)}
            className="flex items-center gap-2.5 bg-slate-800 hover:bg-slate-700/80 border border-slate-700 px-3.5 py-1.5 rounded-lg text-xs font-medium text-slate-200 transition-colors"
          >
            <Building2 className="w-4 h-4 text-emerald-400" />
            <div className="text-left">
              <div className="font-semibold text-slate-100 leading-tight">{activeMerchant.name}</div>
              <div className="text-[10px] text-slate-400 font-mono">ID: {activeMerchant.id.substring(0, 8)}...</div>
            </div>
            <ChevronDown className="w-3.5 h-3.5 text-slate-400 ml-1" />
          </button>

          {dropdownOpen && (
            <>
              <div className="fixed inset-0 z-40" onClick={() => setDropdownOpen(false)} />
              <div className="absolute right-0 mt-2 w-72 bg-slate-800 border border-slate-700 rounded-xl shadow-xl z-50 p-1.5 animate-in fade-in zoom-in-95 duration-100">
                <div className="px-3 py-2 text-[11px] font-semibold text-slate-400 uppercase tracking-wider border-b border-slate-700/50">
                  Switch Active Merchant
                </div>
                <div className="py-1 space-y-1">
                  {availableMerchants.map((merchant) => {
                    const isSelected = merchant.id === activeMerchant.id;
                    return (
                      <button
                        key={merchant.id}
                        type="button"
                        onClick={() => {
                          setActiveMerchant(merchant);
                          setDropdownOpen(false);
                        }}
                        className={`w-full text-left px-3 py-2 rounded-lg text-xs flex items-start justify-between transition-colors ${
                          isSelected
                            ? 'bg-emerald-950/40 text-emerald-300 border border-emerald-500/30'
                            : 'hover:bg-slate-700/50 text-slate-300'
                        }`}
                      >
                        <div>
                          <div className="font-medium text-slate-100">{merchant.name}</div>
                          <div className="text-[10px] text-slate-400 font-mono mt-0.5">{merchant.id}</div>
                          <div className="text-[10px] text-slate-500 mt-0.5">{merchant.description}</div>
                        </div>
                        {isSelected && <Check className="w-4 h-4 text-emerald-400 shrink-0 mt-0.5" />}
                      </button>
                    );
                  })}
                </div>
              </div>
            </>
          )}
        </div>
      </div>
    </header>
  );
};
