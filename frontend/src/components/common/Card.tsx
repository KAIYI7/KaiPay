import React from 'react';
import { clsx } from 'clsx';
import { twMerge } from 'tailwind-merge';

interface CardProps extends React.HTMLAttributes<HTMLDivElement> {
  title?: string;
  subtitle?: string;
  action?: React.ReactNode;
}

export const Card: React.FC<CardProps> = ({
  children,
  title,
  subtitle,
  action,
  className,
  ...props
}) => {
  return (
    <div
      className={twMerge(
        clsx('bg-slate-800/80 border border-slate-700/80 rounded-xl p-5 shadow-sm backdrop-blur-sm', className)
      )}
      {...props}
    >
      {(title || action) && (
        <div className="flex items-center justify-between mb-4 border-b border-slate-700/50 pb-3">
          <div>
            {title && <h3 className="text-base font-semibold text-slate-100">{title}</h3>}
            {subtitle && <p className="text-xs text-slate-400 mt-0.5">{subtitle}</p>}
          </div>
          {action && <div>{action}</div>}
        </div>
      )}
      {children}
    </div>
  );
};
