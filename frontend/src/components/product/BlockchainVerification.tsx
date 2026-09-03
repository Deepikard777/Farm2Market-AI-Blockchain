import { useState, useEffect } from 'react';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Shield, ShieldCheck, ShieldAlert, ShieldX, Loader2, Link2, Upload } from 'lucide-react';
import { API_BASE } from '@/services/api';

interface VerificationStep {
  type: string;
  timestamp: string;
  verified: boolean;
  eventHash: string;
  txHash?: string;
}

interface VerificationResult {
  batchId: string;
  status: 'verified' | 'compromised' | 'unavailable' | 'no_journey' | 'error';
  message: string;
  totalSteps?: number;
  verifiedSteps?: number;
  onChainEventCount?: number;
  steps?: VerificationStep[];
}

interface BlockchainVerificationProps {
  batchId: string;
  autoVerify?: boolean;
}

export function BlockchainVerification({ batchId, autoVerify = false }: BlockchainVerificationProps) {
  const [loading, setLoading] = useState(false);
  const [recording, setRecording] = useState(false);
  const [result, setResult] = useState<VerificationResult | null>(null);
  const [available, setAvailable] = useState<boolean | null>(null);

  // Check blockchain availability on mount
  useEffect(() => {
    fetch(`${API_BASE}/blockchain/status`)
      .then(res => res.json())
      .then(data => {
        setAvailable(data.available);
        if (data.available && autoVerify) {
          handleVerify();
        }
      })
      .catch(() => setAvailable(false));
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
    try {
      const res = await fetch(`${API_BASE}/blockchain/record/${batchId}`, {
        method: 'POST',
      });
      const data = await res.json();
      if (data.status === 'success') {
        // Re-verify after recording
        await handleVerify();
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

  // Check if this is a "not yet recorded" scenario vs actual tampering
  const isUnrecorded = result?.status === 'compromised' &&
    result?.onChainEventCount === 0 &&
    result?.verifiedSteps === 0;

  const getStatusIcon = () => {
    if (!result) return <Shield className="h-5 w-5 text-gray-400" />;
    if (isUnrecorded) return <ShieldAlert className="h-5 w-5 text-yellow-600" />;
    switch (result.status) {
      case 'verified':
        return <ShieldCheck className="h-5 w-5 text-green-600" />;
      case 'compromised':
        return <ShieldX className="h-5 w-5 text-red-600" />;
      case 'unavailable':
        return <ShieldAlert className="h-5 w-5 text-yellow-600" />;
      default:
        return <Shield className="h-5 w-5 text-gray-400" />;
    }
  };

  const getStatusColor = () => {
    if (!result) return 'border-gray-200 dark:border-gray-700';
    if (isUnrecorded) return 'border-yellow-300 dark:border-yellow-800 bg-yellow-50/50 dark:bg-yellow-950/20';
    switch (result.status) {
      case 'verified':
        return 'border-green-300 dark:border-green-800 bg-green-50/50 dark:bg-green-950/20';
      case 'compromised':
        return 'border-red-300 dark:border-red-800 bg-red-50/50 dark:bg-red-950/20';
      case 'unavailable':
        return 'border-yellow-300 dark:border-yellow-800 bg-yellow-50/50 dark:bg-yellow-950/20';
      default:
        return 'border-gray-200 dark:border-gray-700';
    }
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
            Blockchain node is offline. Start the Hardhat node to enable verification.
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
                <p className={`font-semibold text-sm ${
                  result.status === 'verified' ? 'text-green-700 dark:text-green-400' :
                  isUnrecorded ? 'text-yellow-700 dark:text-yellow-400' :
                  result.status === 'compromised' ? 'text-red-700 dark:text-red-400' :
                  'text-gray-700 dark:text-gray-300'
                }`}>
                  {isUnrecorded
                    ? 'Events not yet recorded on blockchain'
                    : result.message}
                </p>
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

            {/* Record on Blockchain button — shown when events aren't on-chain yet */}
            {isUnrecorded && (
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

            {/* Per-step results */}
            {result.steps && result.steps.length > 0 && (
              <div className="space-y-2">
                <p className="text-xs font-medium text-muted-foreground uppercase tracking-wider">
                  Event Verification
                </p>
                {result.steps.map((step, idx) => (
                  <div
                    key={idx}
                    className={`flex items-center justify-between p-2 rounded-lg border text-sm ${
                      step.verified
                        ? 'border-green-200 dark:border-green-800 bg-green-50 dark:bg-green-950/30'
                        : isUnrecorded
                          ? 'border-yellow-200 dark:border-yellow-800 bg-yellow-50 dark:bg-yellow-950/30'
                          : 'border-red-200 dark:border-red-800 bg-red-50 dark:bg-red-950/30'
                    }`}
                  >
                    <div className="flex items-center gap-2 min-w-0 flex-1">
                      {step.verified ? (
                        <ShieldCheck className="h-4 w-4 text-green-600 shrink-0" />
                      ) : isUnrecorded ? (
                        <ShieldAlert className="h-4 w-4 text-yellow-600 shrink-0" />
                      ) : (
                        <ShieldX className="h-4 w-4 text-red-600 shrink-0" />
                      )}
                      <div className="min-w-0">
                        <p className="font-medium text-xs truncate">
                          {step.type}
                        </p>
                        <p className="text-[10px] text-muted-foreground truncate">
                          {new Date(step.timestamp).toLocaleString()}
                        </p>
                      </div>
                    </div>
                    <div className="flex items-center gap-1 shrink-0 ml-2">
                      {step.txHash && (
                        <span className="flex items-center gap-1 text-[10px] text-muted-foreground font-mono">
                          <Link2 className="h-3 w-3" />
                          {step.txHash.slice(0, 10)}...
                        </span>
                      )}
                      <span className={`px-1.5 py-0.5 rounded text-[10px] font-medium ${
                        step.verified
                          ? 'bg-green-100 text-green-800 dark:bg-green-900/50 dark:text-green-300'
                          : isUnrecorded
                            ? 'bg-yellow-100 text-yellow-800 dark:bg-yellow-900/50 dark:text-yellow-300'
                            : 'bg-red-100 text-red-800 dark:bg-red-900/50 dark:text-red-300'
                      }`}>
                        {step.verified ? '✓ On-chain' : isUnrecorded ? '○ Pending' : '✗ Tampered'}
                      </span>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}
      </CardContent>
    </Card>
  );
}
