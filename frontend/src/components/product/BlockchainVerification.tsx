import { useState, useEffect } from 'react';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Shield, ShieldCheck, ShieldAlert, ShieldX, Loader2, Link2, Upload, AlertTriangle, Ban } from 'lucide-react';
import { API_BASE } from '@/services/api';
import type { JourneyStep } from '@/types/product';

/**
 * Per-step state, decided by the backend from the LIVE chain.
 * A txHash stored in PostgreSQL is never treated as proof of existence — the Hardhat chain is
 * ephemeral, so old hashes go stale on every restart.
 */
type StepState = 'verified' | 'pending_review' | 'unrecorded' | 'tampered' | 'rejected';

interface VerificationStep {
  type: string;
  timestamp: string;
  location?: string;
  description?: string;
  verified: boolean;
  stepStatus?: StepState;
  requiresReview?: boolean;
  eventHash: string;
  txHash?: string;
  txConfirmedOnCurrentChain?: boolean;
}

interface VerificationResult {
  batchId: string;
  status:
    | 'verified'
    | 'pending_recording'
    | 'pending_review'
    | 'tampered'
    | 'unavailable'
    | 'no_journey'
    | 'error';
  message: string;
  totalSteps?: number;
  verifiedSteps?: number;
  onChainEventCount?: number;
  pendingReviewCount?: number;
  unrecordedCount?: number;
  tamperedCount?: number;
  rejectedCount?: number;
  steps?: VerificationStep[];
}

interface RecordOutcome {
  status: string;
  recorded: number;
  skipped: number;
  alreadyOnChain: number;
  pendingReview: number;
  rejected: number;
  failed: number;
  message: string;
}

interface BlockchainVerificationProps {
  batchId: string;
  autoVerify?: boolean;
  journey?: JourneyStep[];
}

export function BlockchainVerification({ batchId, autoVerify = false, journey }: BlockchainVerificationProps) {
  const [loading, setLoading] = useState(false);
  const [recording, setRecording] = useState(false);
  const [result, setResult] = useState<VerificationResult | null>(null);
  const [recordOutcome, setRecordOutcome] = useState<RecordOutcome | null>(null);
  const [available, setAvailable] = useState<boolean | null>(null);
  const [availabilityMessage, setAvailabilityMessage] = useState<string>('');

  // Check blockchain availability on mount
  useEffect(() => {
    let cancelled = false;
    fetch(`${API_BASE}/blockchain/status`)
      .then(res => res.json())
      .then(data => {
        if (cancelled) return;
        setAvailable(Boolean(data.available));
        setAvailabilityMessage(data.message || '');
        if (data.available && autoVerify) {
          handleVerify();
        }
      })
      .catch(() => {
        if (cancelled) return;
        setAvailable(false);
        setAvailabilityMessage('Could not reach the backend blockchain service.');
      });
    return () => { cancelled = true; };
  }, [batchId]);

  const handleVerify = async () => {
    setLoading(true);
    try {
      const res = await fetch(`${API_BASE}/blockchain/verify/${batchId}`);
      const data = await res.json();
      setResult(data);
    } catch {
      setResult({
        batchId,
        status: 'error',
        message: 'Failed to connect to verification service',
      });
    } finally {
      setLoading(false);
    }
  };

  const handleRecordOnChain = async () => {
    setRecording(true);
    setRecordOutcome(null);
    try {
      const res = await fetch(`${API_BASE}/blockchain/record/${batchId}`, {
        method: 'POST',
      });
      const data = await res.json();

      if (data.status === 'success' || data.status === 'partial') {
        setRecordOutcome({
          status: data.status,
          recorded: data.recorded ?? 0,
          skipped: data.skipped ?? 0,
          alreadyOnChain: data.alreadyOnChain ?? 0,
          pendingReview: data.pendingReview ?? 0,
          rejected: data.rejected ?? 0,
          failed: data.failed ?? 0,
          message: data.message || '',
        });

        // The backend returns a fresh verification snapshot; prefer it, and re-verify for good measure.
        if (data.verification) {
          setResult(data.verification);
        } else {
          await handleVerify();
        }
      } else {
        setResult({
          batchId,
          status: 'error',
          message: data.message || 'Failed to record events',
        });
      }
    } catch {
      setResult({
        batchId,
        status: 'error',
        message: 'Failed to connect to blockchain service',
      });
    } finally {
      setRecording(false);
    }
  };

  /**
   * Resolve the per-step state. The backend's `stepStatus` is authoritative; the local fallback
   * only exists for older payloads and deliberately NEVER labels a stale txHash as tampering.
   */
  const resolveStepState = (step: VerificationStep, idx: number): StepState => {
    if (step.stepStatus) return step.stepStatus;
    if (step.verified) return 'verified';

    const jStep = journey?.[idx] || journey?.find(j => j.timestamp === step.timestamp);
    if (
      (jStep?.status as unknown as string) === 'PENDING_REVIEW' ||
      Boolean(jStep?.requiresReview) ||
      (Boolean(jStep?.anomalyDetected) && !jStep?.anomalyReviewed)
    ) {
      return 'pending_review';
    }
    if (String((jStep as any)?.anomalyDecision) === 'REJECTED') return 'rejected';

    // No live on-chain record, and no confirmed transaction on the current chain → merely
    // awaiting recording (this is the Hardhat-restart / stale-txHash case, NOT tampering).
    const txConfirmed =
      step.txConfirmedOnCurrentChain ?? Boolean(step.txHash && step.txHash !== 'null');
    return txConfirmed ? 'tampered' : 'unrecorded';
  };

  const stepStates = result?.steps ? result.steps.map((step, idx) => resolveStepState(step, idx)) : [];

  const allVerified = Boolean(result && result.status === 'verified');
  const hasTampered =
    Boolean(result?.tamperedCount && result.tamperedCount > 0) ||
    result?.status === 'tampered' ||
    stepStates.some(s => s === 'tampered');
  const hasPendingReview =
    Boolean(result?.pendingReviewCount && result.pendingReviewCount > 0) ||
    stepStates.some(s => s === 'pending_review');
  const hasUnrecorded =
    Boolean(result?.unrecordedCount && result.unrecordedCount > 0) ||
    result?.status === 'pending_recording' ||
    stepStates.some(s => s === 'unrecorded');

  const getStatusIcon = () => {
    if (!result) return <Shield className="h-5 w-5 text-gray-400" />;
    if (allVerified) return <ShieldCheck className="h-5 w-5 text-green-600" />;
    if (hasTampered) return <ShieldX className="h-5 w-5 text-red-600" />;
    if (hasPendingReview) return <AlertTriangle className="h-5 w-5 text-amber-600" />;
    if (hasUnrecorded) return <ShieldAlert className="h-5 w-5 text-yellow-600" />;
    switch (result.status) {
      case 'unavailable':
        return <ShieldAlert className="h-5 w-5 text-yellow-600" />;
      default:
        return <Shield className="h-5 w-5 text-gray-400" />;
    }
  };

  const getStatusColor = () => {
    if (!result) return 'border-gray-200 dark:border-gray-700';
    if (allVerified) return 'border-green-300 dark:border-green-800 bg-green-50/50 dark:bg-green-950/20';
    if (hasTampered) return 'border-red-300 dark:border-red-800 bg-red-50/50 dark:bg-red-950/20';
    if (hasPendingReview) return 'border-amber-300 dark:border-amber-800 bg-amber-50/50 dark:bg-amber-950/20';
    if (hasUnrecorded) return 'border-yellow-300 dark:border-yellow-800 bg-yellow-50/50 dark:bg-yellow-950/20';
    switch (result.status) {
      case 'unavailable':
        return 'border-yellow-300 dark:border-yellow-800 bg-yellow-50/50 dark:bg-yellow-950/20';
      default:
        return 'border-gray-200 dark:border-gray-700';
    }
  };

  const getSummaryMessage = () => {
    if (!result) return '';
    if (allVerified) return 'All journey events verified on blockchain';
    if (hasTampered) {
      return result.message || 'Event cryptographic verification failed — possible tampering';
    }
    if (hasPendingReview && !hasUnrecorded) {
      return '⚠ Event Held for AI Review';
    }
    if (hasUnrecorded) return 'Pending On-Chain Recording';
    if (hasPendingReview) return '⚠ Event Held for AI Review';
    return result.message;
  };

  const getSubMessage = () => {
    if (!result) return '';
    if (allVerified) return null;
    if (hasTampered) return null;
    if (hasPendingReview && !hasUnrecorded) return 'Blockchain commitment pending approval';
    if (hasUnrecorded) {
      const parts: string[] = [];
      if (result.unrecordedCount) parts.push(`${result.unrecordedCount} event(s) awaiting on-chain recording`);
      if (hasPendingReview) parts.push('1+ held for AI review');
      return parts.join(' • ');
    }
    return null;
  };

  const getMessageTextColor = () => {
    if (!result) return 'text-gray-700 dark:text-gray-300';
    if (allVerified) return 'text-green-700 dark:text-green-400';
    if (hasTampered) return 'text-red-700 dark:text-red-400';
    if (hasPendingReview) return 'text-amber-700 dark:text-amber-400';
    if (hasUnrecorded) return 'text-yellow-700 dark:text-yellow-400';
    return 'text-gray-700 dark:text-gray-300';
  };

  if (available === false) {
    return (
      <Card className="border-yellow-200 dark:border-yellow-800 bg-yellow-50/30 dark:bg-yellow-950/10">
        <CardHeader className="pb-3">
          <CardTitle className="flex items-center gap-2 text-sm">
            <ShieldAlert className="h-4 w-4 text-yellow-600" />
            Blockchain Verification
          </CardTitle>
        </CardHeader>
        <CardContent>
          <p className="text-xs text-yellow-700 dark:text-yellow-400">
            {availabilityMessage ||
              'Blockchain node is offline. Start the Hardhat node to enable verification.'}
          </p>
        </CardContent>
      </Card>
    );
  }

  return (
    <Card className={getStatusColor()}>
      <CardHeader className="pb-3">
        <CardTitle className="flex items-center gap-2">
          {getStatusIcon()}
          Blockchain Verification
        </CardTitle>
        <CardDescription>
          Verify supply chain integrity against immutable blockchain records
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {!result && (
          <Button
            onClick={handleVerify}
            disabled={loading}
            variant="outline"
            className="w-full"
          >
            {loading ? (
              <>
                <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                Verifying on blockchain...
              </>
            ) : (
              <>
                <Shield className="mr-2 h-4 w-4" />
                Verify Journey Integrity
              </>
            )}
          </Button>
        )}

        {result && (
          <div className="space-y-4">
            {/* Summary */}
            <div className="flex items-center justify-between">
              <div>
                <p className={`font-semibold text-sm ${getMessageTextColor()}`}>
                  {getSummaryMessage()}
                </p>
                {getSubMessage() && (
                  <p className="text-xs text-muted-foreground mt-0.5">{getSubMessage()}</p>
                )}
                {result.totalSteps !== undefined && (
                  <p className="text-xs text-muted-foreground mt-1">
                    {result.verifiedSteps}/{result.totalSteps} steps verified •{' '}
                    {result.onChainEventCount} events on-chain
                  </p>
                )}
              </div>
              <Button
                onClick={handleVerify}
                disabled={loading}
                variant="ghost"
                size="sm"
              >
                {loading ? <Loader2 className="h-4 w-4 animate-spin" /> : 'Re-verify'}
              </Button>
            </div>

            {/* Record on Blockchain button — shown when legitimate unrecorded events exist */}
            {hasUnrecorded && !hasTampered && (
              <Button
                onClick={handleRecordOnChain}
                disabled={recording}
                className="w-full bg-yellow-600 hover:bg-yellow-700 text-white"
              >
                {recording ? (
                  <>
                    <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                    Recording events on blockchain...
                  </>
                ) : (
                  <>
                    <Upload className="mr-2 h-4 w-4" />
                    Record Events on Blockchain
                  </>
                )}
              </Button>
            )}

            {/* Accurate outcome of the last recording run */}
            {recordOutcome && (
              <div className="text-xs rounded-md border border-border bg-muted/40 p-2 space-y-0.5">
                <p className="font-medium">
                  {recordOutcome.status === 'success' ? 'Recording complete' : 'Recording partially complete'}
                </p>
                <p className="text-muted-foreground">
                  recorded: {recordOutcome.recorded} • skipped: {recordOutcome.skipped}
                  {recordOutcome.alreadyOnChain > 0 ? ` (already on-chain: ${recordOutcome.alreadyOnChain})` : ''}
                  {' • '}pendingReview: {recordOutcome.pendingReview}
                  {recordOutcome.rejected > 0 ? ` • rejected: ${recordOutcome.rejected}` : ''}
                  {recordOutcome.failed > 0 ? ` • failed: ${recordOutcome.failed}` : ''}
                </p>
              </div>
            )}

            {/* Per-step results */}
            {result.steps && result.steps.length > 0 && (
              <div className="space-y-2">
                <p className="text-xs font-medium text-muted-foreground uppercase tracking-wider">
                  Event Verification
                </p>
                {result.steps.map((step, idx) => {
                  const status = stepStates[idx] || resolveStepState(step, idx);

                  let stepBorderBg = 'border-gray-200 dark:border-gray-800 bg-gray-50 dark:bg-gray-950/30';
                  let StepIcon = <Shield className="h-4 w-4 text-gray-500 shrink-0" />;
                  let badgeStyle = 'bg-gray-100 text-gray-800 dark:bg-gray-900/50 dark:text-gray-300';
                  let badgeText = 'Unknown';

                  if (status === 'verified') {
                    stepBorderBg = 'border-green-200 dark:border-green-800 bg-green-50 dark:bg-green-950/30';
                    StepIcon = <ShieldCheck className="h-4 w-4 text-green-600 shrink-0" />;
                    badgeStyle = 'bg-green-100 text-green-800 dark:bg-green-900/50 dark:text-green-300';
                    badgeText = '✓ On-chain';
                  } else if (status === 'pending_review') {
                    stepBorderBg = 'border-amber-200 dark:border-amber-800 bg-amber-50 dark:bg-amber-950/30';
                    StepIcon = <AlertTriangle className="h-4 w-4 text-amber-600 shrink-0" />;
                    badgeStyle = 'bg-amber-100 text-amber-800 dark:bg-amber-900/50 dark:text-amber-300';
                    badgeText = '⚠️ Held for AI Review';
                  } else if (status === 'rejected') {
                    stepBorderBg = 'border-gray-200 dark:border-gray-700 bg-gray-100 dark:bg-gray-900/40';
                    StepIcon = <Ban className="h-4 w-4 text-gray-500 shrink-0" />;
                    badgeStyle = 'bg-gray-200 text-gray-700 dark:bg-gray-800 dark:text-gray-300';
                    badgeText = '⊘ Rejected by review';
                  } else if (status === 'unrecorded') {
                    stepBorderBg = 'border-yellow-200 dark:border-yellow-800 bg-yellow-50 dark:bg-yellow-950/30';
                    StepIcon = <ShieldAlert className="h-4 w-4 text-yellow-600 shrink-0" />;
                    badgeStyle = 'bg-yellow-100 text-yellow-800 dark:bg-yellow-900/50 dark:text-yellow-300';
                    badgeText = '○ Pending Sync';
                  } else if (status === 'tampered') {
                    stepBorderBg = 'border-red-200 dark:border-red-800 bg-red-50 dark:bg-red-950/30';
                    StepIcon = <ShieldX className="h-4 w-4 text-red-600 shrink-0" />;
                    badgeStyle = 'bg-red-100 text-red-800 dark:bg-red-900/50 dark:text-red-300';
                    badgeText = '✗ Tampered';
                  }

                  // Only surface a txHash when it is meaningful for the current state.
                  const showTxHash =
                    Boolean(step.txHash && step.txHash !== 'null') &&
                    (status === 'verified' || status === 'tampered');

                  const formattedTime = step.timestamp
                    ? new Date(step.timestamp).toLocaleString()
                    : 'unknown time';

                  return (
                    <div
                      key={idx}
                      className={`flex items-center justify-between p-2 rounded-lg border text-sm ${stepBorderBg}`}
                    >
                      <div className="flex items-center gap-2 min-w-0 flex-1">
                        {StepIcon}
                        <div className="min-w-0">
                          <p className="font-medium text-xs truncate">
                            {step.type}
                          </p>
                          <p className="text-[10px] text-muted-foreground truncate">
                            {formattedTime}
                          </p>
                        </div>
                      </div>
                      <div className="flex items-center gap-1 shrink-0 ml-2">
                        {showTxHash && (
                          <span className="flex items-center gap-1 text-[10px] text-muted-foreground font-mono">
                            <Link2 className="h-3 w-3" />
                            {step.txHash!.slice(0, 10)}...
                          </span>
                        )}
                        <span className={`px-1.5 py-0.5 rounded text-[10px] font-medium ${badgeStyle}`}>
                          {badgeText}
                        </span>
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </div>
        )}
      </CardContent>
    </Card>
  );
}
