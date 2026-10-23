import { useRef, useState } from 'react';
import { QRCodeSVG } from 'qrcode.react';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Download, Copy, Check, QrCode, ExternalLink, Info, CheckCircle2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { getProductQrUrl, isLocalhostUrl } from '@/utils/qrUtils';

interface QRCodeDisplayProps {
  qrValue: string;
  productName: string;
  productId?: string;
}

export function QRCodeDisplay({ qrValue, productName, productId }: QRCodeDisplayProps) {
  const qrRef = useRef<HTMLDivElement>(null);
  const [copied, setCopied] = useState(false);

  // Normalize and resolve the QR URL dynamically
  const resolvedUrl = getProductQrUrl(
    { id: productId, qrCode: qrValue }
  );

  const isLocal = isLocalhostUrl(resolvedUrl);

  const handleCopyLink = async () => {
    try {
      await navigator.clipboard.writeText(resolvedUrl);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch (err) {
      console.error('Failed to copy QR URL:', err);
    }
  };

  const handleDownload = () => {
    const svg = qrRef.current?.querySelector('svg');
    if (!svg) return;

    // Serialize SVG with proper attributes
    const svgData = new XMLSerializer().serializeToString(svg);
    const svgBlob = new Blob([svgData], { type: 'image/svg+xml;charset=utf-8' });
    const blobUrl = URL.createObjectURL(svgBlob);

    const canvas = document.createElement('canvas');
    // High-resolution output for crisp scanning and printing
    const scale = 3;
    const size = 200 * scale;
    const padding = 24 * scale;

    canvas.width = size + padding * 2;
    canvas.height = size + padding * 2;
    const ctx = canvas.getContext('2d');

    const img = new Image();
    img.onload = () => {
      if (ctx) {
        // Draw white background
        ctx.fillStyle = '#FFFFFF';
        ctx.fillRect(0, 0, canvas.width, canvas.height);
        // Draw QR
        ctx.drawImage(img, padding, padding, size, size);
      }
      URL.revokeObjectURL(blobUrl);

      const pngFile = canvas.toDataURL('image/png');
      const downloadLink = document.createElement('a');
      const safeName = (productName || 'product')
        .toLowerCase()
        .replace(/[^a-z0-9]+/g, '-')
        .replace(/^-+|-+$/g, '');
      downloadLink.download = `${safeName}-traceability-qr.png`;
      downloadLink.href = pngFile;
      downloadLink.click();
    };

    img.onerror = () => {
      URL.revokeObjectURL(blobUrl);
      console.error('Failed to render QR for download');
    };

    img.src = blobUrl;
  };

  return (
    <Card className="dark:bg-gray-900/50 dark:border-gray-800 backdrop-blur-sm">
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <QrCode className="h-5 w-5 text-primary" />
          Consumer Traceability QR Code
        </CardTitle>
        <CardDescription>
          Scan with any smartphone camera to view verified batch provenance and supply chain journey
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col items-center space-y-4">
        {/* QR Code Container */}
        <div
          ref={qrRef}
          className="p-5 bg-white rounded-xl shadow-md border border-gray-100 flex items-center justify-center"
        >
          <QRCodeSVG
            value={resolvedUrl || 'about:blank'}
            size={220}
            level="H"
            includeMargin={true}
          />
        </div>

        {/* Action Buttons */}
        <div className="flex flex-wrap items-center justify-center gap-2 w-full max-w-sm">
          <Button onClick={handleDownload} variant="outline" className="flex-1 min-w-[140px]">
            <Download className="h-4 w-4 mr-2" />
            Download QR
          </Button>
          <Button
            onClick={handleCopyLink}
            variant="outline"
            className="flex-1 min-w-[140px]"
          >
            {copied ? (
              <>
                <Check className="h-4 w-4 mr-2 text-green-600" />
                Copied!
              </>
            ) : (
              <>
                <Copy className="h-4 w-4 mr-2" />
                Copy Link
              </>
            )}
          </Button>
        </div>

        {/* Scannable URL with Click-Through */}
        <div className="w-full max-w-md text-center">
          <a
            href={resolvedUrl}
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex items-center gap-1.5 text-xs text-primary hover:underline break-all font-mono bg-muted/50 px-3 py-1.5 rounded-md border border-border/50 max-w-full"
          >
            <span className="truncate">{resolvedUrl}</span>
            <ExternalLink className="h-3 w-3 shrink-0" />
          </a>
        </div>

        {/* Testing & LAN Guidance Notice */}
        {isLocal ? (
          <div className="w-full max-w-md p-3 rounded-lg border border-amber-200 dark:border-amber-900/50 bg-amber-50/70 dark:bg-amber-950/20 text-xs text-amber-900 dark:text-amber-200 space-y-1">
            <div className="flex items-center gap-1.5 font-semibold text-amber-800 dark:text-amber-300">
              <Info className="h-4 w-4 shrink-0 text-amber-600 dark:text-amber-400" />
              <span>Mobile Phone Testing Guidance</span>
            </div>
            <p className="leading-relaxed text-amber-800/90 dark:text-amber-300/90">
              This QR code points to <code className="font-mono bg-amber-100/80 dark:bg-amber-900/40 px-1 py-0.5 rounded">localhost</code>, which is reachable only on this machine.
            </p>
            <p className="leading-relaxed text-amber-800/90 dark:text-amber-300/90">
              To scan from a smartphone on the same Wi-Fi, open this web app using your computer's LAN IP (e.g. <code className="font-mono bg-amber-100/80 dark:bg-amber-900/40 px-1 py-0.5 rounded">http://&lt;lan-ip&gt;:5173</code>). For external consumers, always deploy with a public domain or set <code className="font-mono bg-amber-100/80 dark:bg-amber-900/40 px-1 py-0.5 rounded">VITE_APP_URL</code>.
            </p>
          </div>
        ) : (
          <div className="w-full max-w-md p-2.5 rounded-lg border border-green-200 dark:border-green-900/40 bg-green-50/50 dark:bg-green-950/20 text-xs text-green-800 dark:text-green-300 flex items-center gap-2">
            <CheckCircle2 className="h-4 w-4 text-green-600 dark:text-green-400 shrink-0" />
            <span>Ready for network & mobile phone scanning.</span>
          </div>
        )}
      </CardContent>
    </Card>
  );
}
