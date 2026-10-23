import { useEffect, useState, useCallback } from 'react';
import { useParams, Link } from 'react-router-dom';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import {
  MapPin,
  Package,
  Truck,
  Store,
  Sprout,
  PackageCheck,
  ShoppingBag,
  Link2,
  Wifi,
  ShieldCheck,
  AlertCircle,
  RefreshCw,
  Copy,
  Check,
  Calendar,
  Layers,
  ArrowLeft,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { QRCodeDisplay } from '@/components/product/QRCodeDisplay';
import { BlockchainVerification } from '@/components/product/BlockchainVerification';
import { AnomalyBadge } from '@/components/product/AnomalyBadge';
import { getTraceability } from '@/services/api';
import type { Product, JourneyStep } from '@/types/product';

export function ProductPage() {
  const { productId } = useParams<{ productId: string }>();
  const [product, setProduct] = useState<Product | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [idCopied, setIdCopied] = useState(false);

  const loadProduct = useCallback(async () => {
    if (!productId || !productId.trim()) {
      setError('invalid_id');
      setLoading(false);
      return;
    }

    setLoading(true);
    setError(null);

    try {
      const data = await getTraceability(productId.trim());
      if (data && data.id) {
        setProduct(data);
      } else {
        setProduct(null);
        setError('not_found');
      }
    } catch (err) {
      console.error('Error loading traceability data:', err);
      setProduct(null);
      setError('api_error');
    } finally {
      setLoading(false);
    }
  }, [productId]);

  useEffect(() => {
    loadProduct();
  }, [loadProduct]);

  const handleCopyId = () => {
    if (!product?.id) return;
    navigator.clipboard.writeText(product.id);
    setIdCopied(true);
    setTimeout(() => setIdCopied(false), 2000);
  };

  // Loading state
  if (loading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gradient-to-br from-green-50/70 via-blue-50/50 to-slate-50 dark:from-gray-950 dark:via-gray-900 dark:to-gray-950 p-4">
        <div className="text-center space-y-4">
          <div className="animate-spin rounded-full h-12 w-12 border-4 border-primary/20 border-t-primary mx-auto"></div>
          <div className="space-y-1">
            <h2 className="text-lg font-semibold text-gray-900 dark:text-gray-100">
              Verifying Product Traceability
            </h2>
            <p className="text-sm text-muted-foreground">
              Retrieving verified batch records from the supply chain network...
            </p>
          </div>
        </div>
      </div>
    );
  }

  // Invalid ID State
  if (error === 'invalid_id' || !productId) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gradient-to-br from-green-50/70 via-blue-50/50 to-slate-50 dark:from-gray-950 dark:via-gray-900 dark:to-gray-950 p-4">
        <Card className="max-w-md w-full text-center shadow-lg dark:bg-gray-900/80 dark:border-gray-800">
          <CardHeader className="space-y-2">
            <div className="w-12 h-12 rounded-full bg-amber-100 dark:bg-amber-950/40 text-amber-600 dark:text-amber-400 flex items-center justify-center mx-auto">
              <AlertCircle className="h-6 w-6" />
            </div>
            <CardTitle className="text-xl">Invalid Product Batch Code</CardTitle>
            <CardDescription>
              The QR code scanned or URL accessed does not contain a valid batch identifier.
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            <p className="text-xs text-muted-foreground">
              Please re-scan the QR code printed on the physical product packaging.
            </p>
            <Link to="/">
              <Button variant="outline" className="w-full">
                <ArrowLeft className="h-4 w-4 mr-2" />
                Return to Home
              </Button>
            </Link>
          </CardContent>
        </Card>
      </div>
    );
  }

  // API Connection Failure State
  if (error === 'api_error') {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gradient-to-br from-green-50/70 via-blue-50/50 to-slate-50 dark:from-gray-950 dark:via-gray-900 dark:to-gray-950 p-4">
        <Card className="max-w-md w-full text-center shadow-lg dark:bg-gray-900/80 dark:border-gray-800">
          <CardHeader className="space-y-2">
            <div className="w-12 h-12 rounded-full bg-red-100 dark:bg-red-950/40 text-red-600 dark:text-red-400 flex items-center justify-center mx-auto">
              <AlertCircle className="h-6 w-6" />
            </div>
            <CardTitle className="text-xl">Traceability Service Unreachable</CardTitle>
            <CardDescription>
              Could not connect to the Farm2Market backend server to fetch batch details.
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            <p className="text-xs text-muted-foreground">
              Please check your network connection or ensure the backend server is running.
            </p>
            <div className="flex gap-2">
              <Button onClick={() => loadProduct()} className="flex-1">
                <RefreshCw className="h-4 w-4 mr-2" />
                Retry
              </Button>
              <Link to="/" className="flex-1">
                <Button variant="outline" className="w-full">
                  Home
                </Button>
              </Link>
            </div>
          </CardContent>
        </Card>
      </div>
    );
  }

  // Missing / 404 Product State
  if (error === 'not_found' || !product) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gradient-to-br from-green-50/70 via-blue-50/50 to-slate-50 dark:from-gray-950 dark:via-gray-900 dark:to-gray-950 p-4">
        <Card className="max-w-md w-full text-center shadow-lg dark:bg-gray-900/80 dark:border-gray-800">
          <CardHeader className="space-y-2">
            <div className="w-12 h-12 rounded-full bg-gray-100 dark:bg-gray-800 text-gray-600 dark:text-gray-300 flex items-center justify-center mx-auto">
              <Package className="h-6 w-6" />
            </div>
            <CardTitle className="text-xl">Product Batch Not Found</CardTitle>
            <CardDescription>
              Batch <code className="font-mono text-xs bg-muted px-1 py-0.5 rounded">{productId}</code> is not registered in the Farm2Market traceability network.
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            <p className="text-xs text-muted-foreground">
              This product may not be registered yet, or the QR code may have been rescinded.
            </p>
            <Link to="/">
              <Button variant="outline" className="w-full">
                <ArrowLeft className="h-4 w-4 mr-2" />
                Return to Home
              </Button>
            </Link>
          </CardContent>
        </Card>
      </div>
    );
  }

  // Status badge helper
  const getProductStatusBadge = (status: string) => {
    const s = (status || '').toUpperCase();
    const map: Record<string, { bg: string; text: string; label: string }> = {
      CREATED: { bg: 'bg-emerald-100 dark:bg-emerald-950/40', text: 'text-emerald-700 dark:text-emerald-300', label: '🌾 Farm Harvested' },
      BOOKED_TRANSPORT: { bg: 'bg-blue-100 dark:bg-blue-950/40', text: 'text-blue-700 dark:text-blue-300', label: '🚛 Transport Booked' },
      IN_TRANSIT: { bg: 'bg-purple-100 dark:bg-purple-950/40', text: 'text-purple-700 dark:text-purple-300', label: '🚚 In Transit' },
      AT_SELLER: { bg: 'bg-orange-100 dark:bg-orange-950/40', text: 'text-orange-700 dark:text-orange-300', label: '🏪 At Retailer' },
      ASSIGNED_TO_SELLER: { bg: 'bg-amber-100 dark:bg-amber-950/40', text: 'text-amber-700 dark:text-amber-300', label: '🏪 Assigned to Retailer' },
      SOLD: { bg: 'bg-green-100 dark:bg-green-950/40', text: 'text-green-700 dark:text-green-300', label: '✓ Sold to Consumer' },
      PARTIALLY_SOLD: { bg: 'bg-emerald-100 dark:bg-emerald-950/40', text: 'text-emerald-700 dark:text-emerald-300', label: '✓ Partially Sold' },
    };
    return map[s] || { bg: 'bg-gray-100 dark:bg-gray-800', text: 'text-gray-700 dark:text-gray-300', label: status.replace(/_/g, ' ') };
  };

  // Helper to format step details
  const getStepConfig = (step: JourneyStep) => {
    const stepType = step.type || step.status;

    if (stepType === 'LOCATION') {
      return {
        icon: <Sprout className="h-4 w-4" />,
        label: '🌾 Farm Origin — Batch Harvested',
        color: 'text-emerald-600 dark:text-emerald-400',
        bgColor: 'bg-emerald-600',
        borderColor: 'border-emerald-200 dark:border-emerald-800',
        bgLight: 'bg-emerald-50 dark:bg-emerald-950/30',
        detail: step.description || `Batch created by ${product.farmerName || 'Farmer'} (${product.quantity} ${product.unit || 'units'})`,
        subDetail: step.location ? `📍 Origin Farm: ${step.location}` : undefined,
      };
    }
    if (stepType === 'TRANSPORT') {
      const isAccepted = step.status === 'ACCEPTED';
      const isRejected = step.status === 'REJECTED';
      return {
        icon: <Truck className="h-4 w-4" />,
        label: isRejected
          ? '🚛 Logistics Request Declined'
          : isAccepted
          ? '🚛 Transport Dispatch Confirmed'
          : '🚛 Logistics Booking Created',
        color: 'text-blue-600 dark:text-blue-400',
        bgColor: isRejected ? 'bg-red-500' : 'bg-blue-600',
        borderColor: isRejected ? 'border-red-200 dark:border-red-800' : 'border-blue-200 dark:border-blue-800',
        bgLight: isRejected ? 'bg-red-50 dark:bg-red-950/30' : 'bg-blue-50 dark:bg-blue-950/30',
        detail: step.description || (step.transporterName
          ? `Transporter "${step.transporterName}" dispatched for cold-chain delivery`
          : 'Logistics coordination in progress'),
        subDetail: step.location ? `📍 Transit Hub: ${step.location}` : undefined,
      };
    }
    if (stepType === 'TRANSPORTED') {
      return {
        icon: <PackageCheck className="h-4 w-4" />,
        label: '📦 Delivered to Retail Destination',
        color: 'text-indigo-600 dark:text-indigo-400',
        bgColor: 'bg-indigo-600',
        borderColor: 'border-indigo-200 dark:border-indigo-800',
        bgLight: 'bg-indigo-50 dark:bg-indigo-950/30',
        detail: step.description || 'Batch safely delivered and verified at retail destination',
        subDetail: step.location ? `📍 Receiving Location: ${step.location}` : undefined,
      };
    }
    if (stepType === 'SELLER') {
      if (step.status === 'SOLD' || step.status === 'PARTIALLY_SOLD') {
        return {
          icon: <ShoppingBag className="h-4 w-4" />,
          label: step.status === 'SOLD' ? '🛒 Sold to Consumer' : '🛒 Retail Sale Recorded',
          color: 'text-purple-600 dark:text-purple-400',
          bgColor: 'bg-purple-600',
          borderColor: 'border-purple-200 dark:border-purple-800',
          bgLight: 'bg-purple-50 dark:bg-purple-950/30',
          detail: step.description || (step.sellerName ? `Verified purchase from ${step.sellerName}` : 'Consumer purchase confirmed'),
          subDetail: step.location ? `📍 Retail Store: ${step.location}` : undefined,
        };
      }
      return {
        icon: <Store className="h-4 w-4" />,
        label: '🏪 In Stock at Retail Store',
        color: 'text-orange-600 dark:text-orange-400',
        bgColor: 'bg-orange-600',
        borderColor: 'border-orange-200 dark:border-orange-800',
        bgLight: 'bg-orange-50 dark:bg-orange-950/30',
        detail: step.description || (step.sellerName ? `Stocked at ${step.sellerName}` : 'Product stocked for consumer sale'),
        subDetail: step.location ? `📍 Shop Location: ${step.location}` : undefined,
      };
    }
    if (stepType === 'SCAN') {
      return {
        icon: <Wifi className="h-4 w-4" />,
        label: '📡 IoT Edge Checkpoint Scanned',
        color: 'text-cyan-600 dark:text-cyan-400',
        bgColor: 'bg-cyan-600',
        borderColor: 'border-cyan-200 dark:border-cyan-800',
        bgLight: 'bg-cyan-50 dark:bg-cyan-950/30',
        detail: step.description || 'Verified at physical IoT checkpoint via edge scanner',
        subDetail: step.location ? `📍 Checkpoint Location: ${step.location}` : undefined,
      };
    }
    // Default fallback
    return {
      icon: <MapPin className="h-4 w-4" />,
      label: stepType || 'Event Recorded',
      color: 'text-gray-600 dark:text-gray-400',
      bgColor: 'bg-gray-500',
      borderColor: 'border-gray-200 dark:border-gray-700',
      bgLight: 'bg-gray-50 dark:bg-gray-900/30',
      detail: step.description || 'Supply chain event recorded in registry',
      subDetail: step.location ? `📍 ${step.location}` : undefined,
    };
  };

  const statusBadge = getProductStatusBadge(product.status);
  const journeyList = Array.isArray(product.journey) ? product.journey : [];

  return (
    <div className="min-h-screen bg-gradient-to-br from-green-50/70 via-blue-50/40 to-slate-50 dark:from-gray-950 dark:via-gray-900 dark:to-gray-950 p-4 md:p-8">
      <div className="max-w-4xl mx-auto space-y-6">
        {/* Top Header / Public Trust Banner */}
        <div className="flex items-center justify-between flex-wrap gap-4 pb-2 border-b border-border/40">
          <div className="flex items-center gap-2.5">
            <div className="p-2 bg-emerald-600 text-white rounded-lg shadow-sm">
              <ShieldCheck className="h-6 w-6" />
            </div>
            <div>
              <h1 className="text-xl font-bold tracking-tight text-gray-900 dark:text-white">
                Farm2Market Traceability
              </h1>
              <p className="text-xs text-muted-foreground">
                Public consumer verification powered by AI and blockchain integrity
              </p>
            </div>
          </div>
          <span className="inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-xs font-semibold bg-emerald-100 text-emerald-800 dark:bg-emerald-950/60 dark:text-emerald-300">
            <span className="w-2 h-2 rounded-full bg-emerald-500 animate-pulse"></span>
            Verified Traceability Record
          </span>
        </div>

        {/* Product & Batch Summary Card */}
        <Card className="shadow-sm dark:bg-gray-900/60 dark:border-gray-800 backdrop-blur-sm">
          <CardHeader className="pb-4">
            <div className="flex flex-wrap items-start justify-between gap-3">
              <div>
                <CardTitle className="text-2xl font-bold flex items-center gap-2 text-gray-900 dark:text-white">
                  <Package className="h-6 w-6 text-emerald-600 dark:text-emerald-400" />
                  {product.productName}
                </CardTitle>
                <div className="flex items-center gap-2 mt-1 text-xs text-muted-foreground">
                  <span>Batch Code:</span>
                  <code className="font-mono bg-muted/60 px-1.5 py-0.5 rounded text-gray-800 dark:text-gray-200">
                    {product.id}
                  </code>
                  <button
                    onClick={handleCopyId}
                    className="p-1 hover:bg-muted rounded text-muted-foreground hover:text-foreground transition-colors"
                    title="Copy Batch ID"
                  >
                    {idCopied ? (
                      <Check className="h-3.5 w-3.5 text-green-600" />
                    ) : (
                      <Copy className="h-3.5 w-3.5" />
                    )}
                  </button>
                </div>
              </div>
              <span className={`px-3 py-1 rounded-full text-xs font-semibold ${statusBadge.bg} ${statusBadge.text}`}>
                {statusBadge.label}
              </span>
            </div>
          </CardHeader>

          <CardContent className="grid grid-cols-1 md:grid-cols-2 gap-4 pt-2 border-t border-border/40">
            {/* Origin & Farmer */}
            <div className="p-3 rounded-lg bg-muted/40 dark:bg-muted/10 border border-border/40 space-y-1">
              <div className="flex items-center gap-2 text-xs font-medium text-muted-foreground">
                <MapPin className="h-3.5 w-3.5 text-emerald-600" />
                <span>Production & Harvest Location</span>
              </div>
              <p className="text-sm font-semibold text-gray-900 dark:text-gray-100">
                {product.productionLocation || 'Farm Location Verified'}
              </p>
              <div className="flex items-center gap-1.5 pt-1 text-xs text-emerald-700 dark:text-emerald-400">
                <Sprout className="h-3.5 w-3.5" />
                <span>Grown by {product.farmerName || 'Certified Producer'}</span>
              </div>
            </div>

            {/* Batch Details */}
            <div className="p-3 rounded-lg bg-muted/40 dark:bg-muted/10 border border-border/40 space-y-1">
              <div className="flex items-center gap-2 text-xs font-medium text-muted-foreground">
                <Layers className="h-3.5 w-3.5 text-blue-600" />
                <span>Batch Quantity & Registration</span>
              </div>
              <p className="text-sm font-semibold text-gray-900 dark:text-gray-100">
                {product.quantity} {product.unit || 'units'}
              </p>
              {product.createdAt && (
                <div className="flex items-center gap-1.5 pt-1 text-xs text-muted-foreground">
                  <Calendar className="h-3.5 w-3.5" />
                  <span>Harvest Date: {new Date(product.createdAt).toLocaleDateString()}</span>
                </div>
              )}
            </div>

            {/* Retail Store if Assigned */}
            {product.sellerName && (
              <div className="p-3 rounded-lg bg-muted/40 dark:bg-muted/10 border border-border/40 space-y-1 md:col-span-2">
                <div className="flex items-center gap-2 text-xs font-medium text-muted-foreground">
                  <Store className="h-3.5 w-3.5 text-orange-600" />
                  <span>Retail Distribution Partner</span>
                </div>
                <p className="text-sm font-semibold text-gray-900 dark:text-gray-100">
                  {product.sellerName}
                  {product.sellerLocation ? ` • ${product.sellerLocation}` : ''}
                </p>
              </div>
            )}
          </CardContent>
        </Card>

        {/* Supply Chain Journey Timeline */}
        <Card className="shadow-sm dark:bg-gray-900/60 dark:border-gray-800 backdrop-blur-sm">
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-lg text-gray-900 dark:text-white">
              <Package className="h-5 w-5 text-primary" />
              Verified Supply Chain Journey
            </CardTitle>
            <CardDescription>
              Chronological custody trail from farm harvest to consumer delivery
            </CardDescription>
          </CardHeader>
          <CardContent>
            {journeyList.length === 0 ? (
              <div className="p-6 text-center text-sm text-muted-foreground italic">
                No journey events recorded for this batch yet.
              </div>
            ) : (
              <div className="space-y-0 pt-2">
                {journeyList.map((step, index) => {
                  const isLast = index === journeyList.length - 1;
                  const config = getStepConfig(step);

                  return (
                    <div key={step.id || index} className="relative flex gap-4">
                      {/* Timeline bar and node */}
                      <div className="flex flex-col items-center">
                        <div
                          className={`w-8 h-8 rounded-full ${config.bgColor} flex items-center justify-center text-white shadow-sm z-10 shrink-0`}
                        >
                          {config.icon}
                        </div>
                        {!isLast && (
                          <div className="w-0.5 bg-gray-200 dark:bg-gray-700 flex-1 min-h-[28px]" />
                        )}
                      </div>

                      {/* Step Card Content */}
                      <div className={`flex-1 ${!isLast ? 'pb-4' : 'pb-0'}`}>
                        <div
                          className={`p-3.5 rounded-lg border ${config.borderColor} ${config.bgLight} shadow-xs space-y-1.5`}
                        >
                          {/* Header */}
                          <div className="flex items-start justify-between gap-2">
                            <span className={`font-semibold text-sm ${config.color}`}>
                              {config.label}
                            </span>
                            <span className="text-[11px] text-muted-foreground whitespace-nowrap">
                              {step.timestamp ? new Date(step.timestamp).toLocaleString() : ''}
                            </span>
                          </div>

                          {/* Detail */}
                          <p className="text-sm text-gray-700 dark:text-gray-300 leading-relaxed">
                            {config.detail}
                          </p>

                          {/* Sub Detail / Location */}
                          {config.subDetail && (
                            <p className="text-xs text-muted-foreground font-medium">
                              {config.subDetail}
                            </p>
                          )}

                          {/* Footer with blockchain tx & anomaly badge */}
                          <div className="pt-1 flex items-center justify-between flex-wrap gap-2 text-xs">
                            <div className="flex items-center gap-2">
                              {(step.anomalyDetected || step.anomalyScore !== undefined) && (
                                <AnomalyBadge
                                  score={step.anomalyScore}
                                  isSuspicious={step.anomalyDetected}
                                  isApproved={Boolean((step as any).anomalyReviewed)}
                                />
                              )}
                            </div>

                            {step.txHash && (
                              <span className="flex items-center gap-1 text-[11px] font-mono text-muted-foreground">
                                <Link2 className="h-3 w-3 text-primary" />
                                tx: {step.txHash.slice(0, 10)}...{step.txHash.slice(-6)}
                              </span>
                            )}
                          </div>
                        </div>
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </CardContent>
        </Card>

        {/* Smart Contract Blockchain Verification Card */}
        <BlockchainVerification
          batchId={product.id}
          autoVerify={true}
          journey={journeyList}
        />

        {/* Consumer QR Code Card */}
        <QRCodeDisplay
          qrValue={product.qrCode}
          productName={product.productName}
          productId={product.id}
        />
      </div>
    </div>
  );
}
