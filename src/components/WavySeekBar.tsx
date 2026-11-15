import React, { useEffect, useRef, useState } from 'react';
import { VideoHatakThemePalette } from '../data/themes';
import { formatTime } from '../data/initialMedia';

interface WavySeekBarProps {
  currentSec: number;
  durationSec: number;
  palette: VideoHatakThemePalette;
  isPlaying: boolean;
  thumbnailSrc: string;
  onScrubStart?: () => void;
  onScrubChange: (sec: number) => void;
  onScrubEnd: (sec: number) => void;
}

export const WavySeekBar: React.FC<WavySeekBarProps> = ({
  currentSec,
  durationSec,
  palette,
  isPlaying,
  thumbnailSrc,
  onScrubStart,
  onScrubChange,
  onScrubEnd,
}) => {
  const containerRef = useRef<HTMLDivElement>(null);
  const [isDragging, setIsDragging] = useState(false);
  const [dragFraction, setDragFraction] = useState(0);
  const [phase, setPhase] = useState(0);

  const safeDuration = Math.max(1, durationSec);
  const actualFraction = Math.min(1, Math.max(0, currentSec / safeDuration));
  const activeFraction = isDragging ? dragFraction : actualFraction;

  useEffect(() => {
    if (!isPlaying) return;
    let raf = 0;
    let last = performance.now();
    const tick = (now: number) => {
      const dt = (now - last) / 1000;
      last = now;
      setPhase((p) => (p + dt * 5.2) % (Math.PI * 2));
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [isPlaying]);

  const fractionFromPointer = (clientX: number): number => {
    const el = containerRef.current;
    if (!el) return activeFraction;
    const rect = el.getBoundingClientRect();
    return Math.min(1, Math.max(0, (clientX - rect.left) / Math.max(1, rect.width)));
  };

  const handlePointerDown = (e: React.PointerEvent) => {
    e.stopPropagation();
    (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
    const f = fractionFromPointer(e.clientX);
    setIsDragging(true);
    setDragFraction(f);
    onScrubStart?.();
    onScrubChange(Math.round(f * safeDuration));
  };

  const handlePointerMove = (e: React.PointerEvent) => {
    if (!isDragging) return;
    e.stopPropagation();
    const f = fractionFromPointer(e.clientX);
    setDragFraction(f);
    onScrubChange(Math.round(f * safeDuration));
  };

  const handlePointerUp = (e: React.PointerEvent) => {
    if (!isDragging) return;
    e.stopPropagation();
    const f = fractionFromPointer(e.clientX);
    setIsDragging(false);
    onScrubEnd(Math.round(f * safeDuration));
  };

  // Generate smooth sine wave path matching WavySeekBar.kt Design #4
  const width = 320;
  const height = 24;
  const centerY = height / 2;
  const thumbX = activeFraction * width;
  const amplitude = isPlaying || isDragging ? 3.2 : 2.1;
  const wavelength = 22;

  const wavePoints: string[] = [];
  const steps = Math.max(2, Math.floor(thumbX / 2));
  for (let i = 0; i <= steps; i++) {
    const x = (i / steps) * thumbX;
    const angle = (x / wavelength) * Math.PI * 2 - phase;
    const fade = thumbX < 14 ? thumbX / 14 : 1;
    const y = centerY + Math.sin(angle) * amplitude * fade;
    wavePoints.push(`${i === 0 ? 'M' : 'L'} ${x.toFixed(1)} ${y.toFixed(1)}`);
  }
  const wavePath = thumbX > 1 ? wavePoints.join(' ') : `M 0 ${centerY} L 1 ${centerY}`;
  const previewSec = Math.round(activeFraction * safeDuration);
  const gradId = `wavyGrad_${palette.id}`;
  const glowId = `wavyGlow_${palette.id}`;

  return (
    <div
      ref={containerRef}
      dir="ltr"
      onPointerDown={handlePointerDown}
      onPointerMove={handlePointerMove}
      onPointerUp={handlePointerUp}
      onClick={(e) => e.stopPropagation()}
      className="relative w-full h-9 flex items-center cursor-pointer select-none touch-none"
      aria-label="شريط تقديم الفيديو"
    >
      {/* Floating Thumbnail Preview Card while scrubbing */}
      {isDragging && (
        <div
          className="absolute -top-24 -translate-x-1/2 pointer-events-none z-30 flex flex-col items-center"
          style={{ left: `${Math.min(86, Math.max(14, activeFraction * 100))}%` }}
        >
          <div
            className="rounded-lg overflow-hidden shadow-2xl border p-0.5"
            style={{
              backgroundColor: palette.surface,
              borderColor: palette.accent,
            }}
          >
            <img
              src={thumbnailSrc}
              alt="معاينة الموضع"
              referrerPolicy="no-referrer"
              className="w-24 h-14 object-cover rounded"
            />
            <div className="text-center py-0.5 text-[11px] font-mono-tabular font-semibold" style={{ color: palette.onSurface }}>
              {formatTime(previewSec)}
            </div>
          </div>
        </div>
      )}

      <svg
        viewBox={`0 0 ${width} ${height}`}
        preserveAspectRatio="none"
        className="w-full h-6 overflow-visible"
      >
        <defs>
          <linearGradient id={gradId} x1="0%" y1="0%" x2="100%" y2="0%">
            <stop offset="0%" stopColor={palette.seekBarPlayedStart} />
            <stop offset="100%" stopColor={palette.seekBarPlayedEnd} />
          </linearGradient>
          <filter id={glowId} x="-10%" y="-80%" width="120%" height="260%">
            <feGaussianBlur stdDeviation="2.4" result="blur" />
            <feMerge>
              <feMergeNode in="blur" />
              <feMergeNode in="SourceGraphic" />
            </feMerge>
          </filter>
        </defs>

        {/* Unplayed flat track */}
        <line
          x1={Math.max(0, thumbX)}
          y1={centerY}
          x2={width}
          y2={centerY}
          stroke={palette.seekBarUnplayed}
          strokeWidth={3}
          strokeLinecap="round"
        />

        {/* Glow underlay */}
        <path
          d={wavePath}
          fill="none"
          stroke={palette.seekBarGlow}
          strokeWidth={6}
          strokeLinecap="round"
          strokeLinejoin="round"
          opacity={0.7}
        />

        {/* Played glowing sine wave */}
        <path
          d={wavePath}
          fill="none"
          stroke={`url(#${gradId})`}
          strokeWidth={3.1}
          strokeLinecap="round"
          strokeLinejoin="round"
          filter={`url(#${glowId})`}
        />

        {/* Thumb outer glow + crisp center */}
        <circle cx={thumbX} cy={centerY} r={isDragging ? 9 : 6.5} fill={palette.seekBarGlow} />
        <circle cx={thumbX} cy={centerY} r={isDragging ? 5.8 : 4.2} fill={palette.seekBarThumb} />
      </svg>
    </div>
  );
};
