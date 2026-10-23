import { AlertTriangle, ShieldCheck } from 'lucide-react';

interface AnomalyBadgeProps {
  score?: number;
  signals?: string[];
  isSuspicious?: boolean;
  isApproved?: boolean;
}

export function AnomalyBadge({ score, signals, isSuspicious, isApproved }: AnomalyBadgeProps) {
  if (isApproved) {
    return (
      <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded text-[11px] font-medium bg-emerald-100 dark:bg-emerald-950/40 text-emerald-800 dark:text-emerald-300 border border-emerald-300 dark:border-emerald-800">
        <ShieldCheck className="h-3 w-3 text-emerald-600 dark:text-emerald-400" />
        AI: Reviewed & Verified
      </span>
    );
  }

  if (isSuspicious || (score !== undefined && score >= 0.5)) {
    return (
      <div className="inline-flex flex-col gap-0.5">
        <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded text-[11px] font-semibold bg-amber-100 dark:bg-amber-950/50 text-amber-800 dark:text-amber-300 border border-amber-300 dark:border-amber-800">
          <AlertTriangle className="h-3 w-3 text-amber-600 dark:text-amber-400 animate-pulse" />
          Held for AI Anomaly Review {score ? `(${(score * 100).toFixed(0)}%)` : ''}
        </span>
        {signals && signals.length > 0 && (
          <span className="text-[10px] text-amber-700 dark:text-amber-400 italic">
            Signal: {signals[0]}
          </span>
        )}
      </div>
    );
  }

  return null;
}
