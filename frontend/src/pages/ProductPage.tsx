import { useEffect, useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { MapPin, User, Package, Truck, Store, DollarSign, ExternalLink, Sprout, PackageCheck, ShoppingBag, Link2, Wifi } from 'lucide-react';
import { QRCodeDisplay } from '@/components/product/QRCodeDisplay';
import { BlockchainVerification } from '@/components/product/BlockchainVerification';
import { getProducts } from '@/services/api';
import type { Product } from '@/types/product';

export function ProductPage() {
  const { productId } = useParams<{ productId: string }>();
  const [product, setProduct] = useState<Product | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    // Load product from backend API
    const loadProduct = async () => {
      if (!productId) {
        setLoading(false);
        return;
      }

      try {
        const products = await getProducts();
        const found = products.find((p: Product) => p.id === productId);
        if (found) {
          setProduct(found);
        }
      } catch (error) {
        console.error('Error loading product:', error);
      } finally {
        setLoading(false);
      }
    };

    loadProduct();
  }, [productId]);

  if (loading) {
    return (
      <div className="min-h-screen flex items-center justify-center">
        <div className="text-center">
          <div className="animate-spin rounded-full h-12 w-12 border-b-2 border-primary mx-auto"></div>
          <p className="mt-4 text-muted-foreground">Loading product...</p>
        </div>
      </div>
    );
  }

  if (!product) {
    return (
      <div className="min-h-screen flex items-center justify-center">
        <div className="text-center">
          <h1 className="text-2xl font-bold mb-4">Product Not Found</h1>
          <p className="text-muted-foreground">The product you're looking for doesn't exist.</p>
        </div>
      </div>
    );
  }

  // Helper to get step config based on type/status
  const getStepConfig = (step: any) => {
    const stepType = step.type || step.status;

    if (stepType === 'LOCATION') {
      return {
        icon: <Sprout className="h-4 w-4" />,
        label: '🌾 Farmer — Batch Created',
        color: 'text-emerald-600 dark:text-emerald-400',
        bgColor: 'bg-emerald-600',
        borderColor: 'border-emerald-200 dark:border-emerald-800',
        bgLight: 'bg-emerald-50 dark:bg-emerald-950/30',
        detail: `${product.farmerName} created a batch of ${product.quantity}${product.unit ? ' ' + product.unit : ''} of ${product.productName}`,
        subDetail: step.location ? `📍 Farm location: ${step.location}` : undefined,
      };
    }
    if (stepType === 'TRANSPORT') {
      return {
        icon: <Truck className="h-4 w-4" />,
        label: step.status === 'PENDING' ? '🚛 Transport — Requested' :
               step.status === 'ACCEPTED' ? '🚛 Transport — Accepted' :
               step.status === 'REJECTED' ? '🚛 Transport — Rejected' :
               '🚛 Transport — Booked',
        color: 'text-blue-600 dark:text-blue-400',
        bgColor: step.status === 'REJECTED' ? 'bg-red-500' : 'bg-blue-600',
        borderColor: step.status === 'REJECTED' ? 'border-red-200 dark:border-red-800' : 'border-blue-200 dark:border-blue-800',
        bgLight: step.status === 'REJECTED' ? 'bg-red-50 dark:bg-red-950/30' : 'bg-blue-50 dark:bg-blue-950/30',
        detail: step.transporterName
          ? `Transporter "${step.transporterName}" ${step.status === 'ACCEPTED' ? 'accepted the shipment' : step.status === 'REJECTED' ? 'rejected the request' : 'assigned for delivery'}`
          : 'Transport requested — awaiting transporter acceptance',
        subDetail: step.location ? `📍 Pickup: ${step.location}` : undefined,
      };
    }
    if (stepType === 'TRANSPORTED') {
      return {
        icon: <PackageCheck className="h-4 w-4" />,
        label: '📦 Delivered to Seller',
        color: 'text-indigo-600 dark:text-indigo-400',
        bgColor: 'bg-indigo-600',
        borderColor: 'border-indigo-200 dark:border-indigo-800',
        bgLight: 'bg-indigo-50 dark:bg-indigo-950/30',
        detail: step.description || 'Product successfully transported and delivered to seller location',
        subDetail: step.location ? `📍 Delivered to: ${step.location}` : undefined,
      };
    }
    if (stepType === 'SELLER') {
      if (step.status === 'SOLD' || step.status === 'PARTIALLY_SOLD') {
        return {
          icon: <ShoppingBag className="h-4 w-4" />,
          label: step.status === 'SOLD' ? '🛒 Sold to Consumer' : '🛒 Partially Sold',
          color: 'text-purple-600 dark:text-purple-400',
          bgColor: 'bg-purple-600',
          borderColor: 'border-purple-200 dark:border-purple-800',
          bgLight: 'bg-purple-50 dark:bg-purple-950/30',
          detail: step.description || (step.sellerName
            ? `Sold by ${step.sellerName}${step.quantity ? ` — ${step.quantity} units` : ''}${step.price ? ` at ₹${step.price}` : ''}`
            : 'Product sold to consumer'),
          subDetail: step.location ? `📍 Sold at: ${step.location}` : undefined,
        };
      }
      return {
        icon: <Store className="h-4 w-4" />,
        label: step.status === 'PENDING' ? '🏪 Seller — Requested' :
               step.status === 'ACCEPTED' ? '🏪 Seller — Accepted' :
               step.status === 'REJECTED' ? '🏪 Seller — Rejected' :
               '🏪 Seller — Assigned',
        color: 'text-orange-600 dark:text-orange-400',
        bgColor: step.status === 'REJECTED' ? 'bg-red-500' : 'bg-orange-600',
        borderColor: step.status === 'REJECTED' ? 'border-red-200 dark:border-red-800' : 'border-orange-200 dark:border-orange-800',
        bgLight: step.status === 'REJECTED' ? 'bg-red-50 dark:bg-red-950/30' : 'bg-orange-50 dark:bg-orange-950/30',
        detail: step.sellerName
          ? `Seller "${step.sellerName}" ${step.status === 'ACCEPTED' ? 'accepted the product for sale' : step.status === 'REJECTED' ? 'rejected the product' : 'has been assigned this product'}`
          : 'Seller assignment requested',
        subDetail: step.location ? `📍 Shop location: ${step.location}` : undefined,
      };
    }
    if (stepType === 'SCAN') {
      return {
        icon: <Wifi className="h-4 w-4" />,
        label: '📡 QR Scanned — IoT Node',
        color: 'text-cyan-600 dark:text-cyan-400',
        bgColor: 'bg-cyan-600',
        borderColor: 'border-cyan-200 dark:border-cyan-800',
        bgLight: 'bg-cyan-50 dark:bg-cyan-950/30',
        detail: step.description || 'QR code scanned by Raspberry Pi edge node',
        subDetail: step.location ? `📍 Scanned at: ${step.location}` : undefined,
      };
    }
    // Fallback
    return {
      icon: <MapPin className="h-4 w-4" />,
      label: stepType || 'Event',
      color: 'text-gray-600 dark:text-gray-400',
      bgColor: 'bg-gray-500',
      borderColor: 'border-gray-200 dark:border-gray-700',
      bgLight: 'bg-gray-50 dark:bg-gray-900/30',
      detail: step.description || 'Event recorded',
      subDetail: step.location ? `📍 ${step.location}` : undefined,
    };
  };

  // Status badge map
  const getStatusBadge = (status: string) => {
    const statusMap: Record<string, { bg: string; text: string; label: string }> = {
      COMPLETED: { bg: 'bg-green-100 dark:bg-green-900/40', text: 'text-green-700 dark:text-green-300', label: '✓ Completed' },
      ACCEPTED: { bg: 'bg-blue-100 dark:bg-blue-900/40', text: 'text-blue-700 dark:text-blue-300', label: '✓ Accepted' },
      REJECTED: { bg: 'bg-red-100 dark:bg-red-900/40', text: 'text-red-700 dark:text-red-300', label: '✗ Rejected' },
      PENDING: { bg: 'bg-yellow-100 dark:bg-yellow-900/40', text: 'text-yellow-700 dark:text-yellow-300', label: '⏳ Pending' },
      SOLD: { bg: 'bg-purple-100 dark:bg-purple-900/40', text: 'text-purple-700 dark:text-purple-300', label: '💰 Sold' },
      PARTIALLY_SOLD: { bg: 'bg-purple-100 dark:bg-purple-900/40', text: 'text-purple-700 dark:text-purple-300', label: '💰 Partially Sold' },
      TRANSPORTED: { bg: 'bg-indigo-100 dark:bg-indigo-900/40', text: 'text-indigo-700 dark:text-indigo-300', label: '📦 Delivered' },
    };
    return statusMap[status] || { bg: 'bg-gray-100 dark:bg-gray-800', text: 'text-gray-700 dark:text-gray-300', label: status };
  };

  return (
    <div className="min-h-screen bg-gradient-to-br from-green-50 to-blue-50 dark:from-gray-950 dark:to-gray-900 p-4">
      <div className="max-w-4xl mx-auto space-y-6">
        {/* Product Info Card */}
        <Card className="dark:bg-gray-900/50 dark:border-gray-800 backdrop-blur-sm">
          <CardHeader>
            <CardTitle className="flex items-center gap-2 dark:text-white">
              <Package className="h-5 w-5" />
              {product.productName}
            </CardTitle>
            <CardDescription className="dark:text-gray-400">Product ID: {product.id}</CardDescription>
          </CardHeader>
          <CardContent className="space-y-6">
            {/* Production Location */}
            <div className="flex items-start gap-3">
              <MapPin className="h-5 w-5 text-muted-foreground mt-0.5" />
              <div>
                <p className="font-medium dark:text-gray-200">Production Location</p>
                <p className="text-sm text-muted-foreground dark:text-gray-400">{product.productionLocation}</p>
              </div>
            </div>

            {/* Farmer Profile Link */}
            <div className="flex items-start gap-3">
              <User className="h-5 w-5 text-muted-foreground mt-0.5" />
              <div>
                <p className="font-medium dark:text-gray-200">Farmer</p>
                <Link
                  to={`/farmer/${product.farmerId}`}
                  className="text-sm text-primary hover:underline flex items-center gap-1 dark:text-primary/80"
                >
                  {product.farmerName}
                  <ExternalLink className="h-3 w-3" />
                </Link>
              </div>
            </div>

            {/* Price Information */}
            <div className="flex items-start gap-3">
              <DollarSign className="h-5 w-5 text-muted-foreground mt-0.5" />
              <div className="space-y-1">
                <p className="font-medium dark:text-gray-200">Pricing</p>
                {product.sellerPrice ? (
                  <div className="space-y-1">
                    <p className="text-sm dark:text-gray-300">
                      <span className="font-medium">Selling Price:</span> ₹{product.sellerPrice}
                      {product.unit && ` per ${product.unit}`}
                      {product.sellerName && ` (Sold by ${product.sellerName})`}
                    </p>
                    {product.farmerPrice && (
                      <p className="text-xs text-muted-foreground dark:text-gray-500">
                        Farmer charge to seller: ₹{product.farmerPrice}
                      </p>
                    )}
                  </div>
                ) : product.farmerPrice ? (
                  <p className="text-sm dark:text-gray-300">
                    <span className="font-medium">Farmer Charge:</span> ₹{product.farmerPrice}
                    {product.sellerName && ` (Assigned to ${product.sellerName})`}
                  </p>
                ) : (
                  <p className="text-sm text-muted-foreground dark:text-gray-500">Not out for sale</p>
                )}
              </div>
            </div>
          </CardContent>
        </Card>

        {/* Supply Chain Journey */}
        <Card className="dark:bg-gray-900/50 dark:border-gray-800 backdrop-blur-sm">
          <CardHeader>
            <CardTitle className="flex items-center gap-2 dark:text-white text-lg">
              <Package className="h-5 w-5" />
              Supply Chain Journey
            </CardTitle>
            <CardDescription className="dark:text-gray-400">
              Track this product from farm to market — every step recorded and verifiable
            </CardDescription>
          </CardHeader>
          <CardContent>
            {product.journey.length === 0 ? (
              <p className="text-sm text-muted-foreground italic dark:text-gray-500">No journey history yet.</p>
            ) : (
              <div className="space-y-0">
                {product.journey.map((step, index) => {
                  const isLast = index === product.journey.length - 1;
                  const config = getStepConfig(step);
                  const badge = getStatusBadge(step.status);

                  return (
                    <div key={step.id || index} className="relative flex gap-4">
                      {/* Timeline connector */}
                      <div className="flex flex-col items-center">
                        <div className={`w-8 h-8 rounded-full ${config.bgColor} flex items-center justify-center text-white shadow-md z-10 shrink-0`}>
                          {config.icon}
                        </div>
                        {!isLast && (
                          <div className="w-0.5 bg-gray-200 dark:bg-gray-700 flex-1 min-h-[24px]" />
                        )}
                      </div>

                      {/* Content */}
                      <div className={`flex-1 ${!isLast ? 'pb-4' : 'pb-0'}`}>
                        <div className={`p-3 rounded-lg border ${config.borderColor} ${config.bgLight} shadow-sm`}>
                          {/* Header */}
                          <div className="flex items-start justify-between gap-2 mb-1.5">
                            <span className={`font-semibold text-sm ${config.color}`}>
                              {config.label}
                            </span>
                            <span className="text-[11px] text-muted-foreground dark:text-gray-500 whitespace-nowrap">
                              {new Date(step.timestamp).toLocaleString()}
                            </span>
                          </div>

                          {/* Detail */}
                          <p className="text-sm text-gray-700 dark:text-gray-300 leading-relaxed">
                            {config.detail}
                          </p>

                          {/* Sub-detail (location) */}
                          {config.subDetail && (
                            <p className="text-xs text-muted-foreground dark:text-gray-500 mt-1">
                              {config.subDetail}
                            </p>
                          )}

                          {/* Sale details */}
                          {(step.status === 'SOLD' || step.status === 'PARTIALLY_SOLD') && (step.quantity || step.price) && (
                            <div className="mt-2 flex gap-3 text-xs">
                              {step.quantity && (
                                <span className="bg-purple-100 dark:bg-purple-900/30 text-purple-700 dark:text-purple-300 px-2 py-0.5 rounded font-medium">
                                  Qty: {step.quantity}{product.unit ? ` ${product.unit}` : ''}
                                </span>
                              )}
                              {step.price && (
                                <span className="bg-purple-100 dark:bg-purple-900/30 text-purple-700 dark:text-purple-300 px-2 py-0.5 rounded font-medium">
                                  ₹{step.price}{product.unit ? `/${product.unit}` : ''}
                                </span>
                              )}
                            </div>
                          )}

                          {/* Footer: status badge + blockchain tx */}
                          <div className="mt-2 flex items-center justify-between flex-wrap gap-2">
                            <span className={`px-2 py-0.5 rounded text-[11px] font-medium ${badge.bg} ${badge.text}`}>
                              {badge.label}
                            </span>
                            {step.txHash && (
                              <span className="flex items-center gap-1 text-[10px] text-muted-foreground dark:text-gray-500 font-mono">
                                <Link2 className="h-3 w-3" />
                                tx: {step.txHash.slice(0, 12)}...
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

        {/* Blockchain Verification */}
        <BlockchainVerification batchId={product.id} autoVerify={true} />

        {/* QR Code Display */}
        <QRCodeDisplay qrValue={product.qrCode} productName={product.productName} />
      </div>
    </div>
  );
}
