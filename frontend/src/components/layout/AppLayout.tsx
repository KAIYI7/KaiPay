import React, { useRef, useState } from 'react';
import { Outlet } from 'react-router-dom';
import { Navbar } from './Navbar';
import { Sidebar } from './Sidebar';

export const AppLayout: React.FC = () => {
  const main = useRef<HTMLElement>(null);
  const [navigationOpen, setNavigationOpen] = useState(false);
  return (
    <div className="min-h-screen bg-slate-950 flex flex-col text-slate-100 font-sans">
      <a href="#main-content" className="skip-link">Skip to content</a>
      <Navbar navigationOpen={navigationOpen} toggleNavigation={() => setNavigationOpen(!navigationOpen)} />
      <div className="flex flex-col lg:flex-row flex-1 min-w-0">
        <Sidebar open={navigationOpen} onNavigate={() => { setNavigationOpen(false); main.current?.focus(); }} />
        <main ref={main} id="main-content" tabIndex={-1} className="flex-1 min-w-0 w-full p-4 md:p-6 xl:p-8 max-w-7xl">
          <Outlet />
        </main>
      </div>
    </div>
  );
};
