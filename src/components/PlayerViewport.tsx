import React, { useEffect, useRef, useState } from 'react';
import {
  ArrowRight,
  Camera,
  Captions,
  FastForward,
  Lock,
  Unlock,
  Maximize2,
  Minimize2,
  Pause,
  Play,
  Repeat,
  Repeat1,
  RotateCcw,
  RotateCw,
  ScreenShare,
  Shuffle,
  SkipBack,
  SkipForward,
  Square,
  Sun,
  Volume2,
  ZoomIn,
} from 'lucide-react';
import { VideoHatakThemePalette } from '../data/themes';
import { PLAYBACK_MODES, PlaybackMode, VirtualVideo, formatTime } from '../data/initialMedia';
import { WavySeekBar } from './WavySeekBar';

export type ResizeModeLabel = 'ملاءمة' | 'تكبير' | 'تمديد';

interface PlayerViewportProps {
  video: VirtualVideo;
  palette: VideoHatakThemePalette;
  isPlaying: boolean;
  currentSec: number;
  savedSpeed: number;
  isTempSpeedActive: boolean;
  playbackMode: PlaybackMode;
  isLandscape: boolean;
  hasNext: boolean;
  hasPrevious: boolean;
  zoomScale: number;
  onTogglePlay: () => void;
  onSeek: (sec: number) => void;
  onScrubEnd: (sec: number) => void;
  onSetSavedSpeed: (speed: number) => void;
  onSetTempSpeed: (active: boolean) => void;
  onCyclePlaybackMode: () => void;
  onNextTrack: () => void;
  onPreviousTrack: () => void;
  onToggleLandscape: () => void;
  onSetZoomScale: (zoom: number) => void;
  onMinimize: () => void;
  onFrameCaptured: (dataUrl: string, filename: string) => void;
  onToast: (message: string) => void;
}

export const PlayerViewport: React.FC<PlayerViewportProps> = ({
  video,
  palette,
  isPlaying,
  currentSec,
  savedSpeed,
  isTempSpeedActive,
  playbackMode,
  isLandscape,
  hasNext,
  hasPrevious,
  zoomScale,
  onTogglePlay,
  onSeek,
  onScrubEnd,
  onSetSavedSpeed,
  onSetTempSpeed,
  onCyclePlaybackMode,
  onNextTrack,
  onPreviousTrack,
  onToggleLandscape,
  onSetZoomScale,
  onMinimize,
  onFrameCaptured,
  onToast,
}) => {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const imgRef = useRef<HTMLImageElement | null>(null);
  const [controlsVisible, setControlsVisible] = useState(true);
  const [isLocked, setIsLocked] = useState(false);
  const [showSubtitles, setShowSubtitles] = useState(true);
  const [resizeMode, setResizeMode] = useState<ResizeModeLabel>('ملاءمة');
  const [volume, setVolume] = useState(80);
  const [brightness, setBrightness] = useState(90);
  const [hudNotice, setHudNotice] = useState<{ icon: 'seek' | 'volume' | 'brightness'; text: string } | null>(null);
  const [showZoomSlider, setShowZoomSlider] = useState(false);

  const holdTimerRef = useRef<number | null>(null);
  const holdTriggeredRef = useRef(false);
  const lastTapRef = useRef<{ time: number; zone: 'left' | 'center' | 'right' } | null>(null);
  const singleTapTimerRef = useRef<number | null>(null);
  const gestureStartRef = useRef<{ x: number; y: number; startSec: number; startVol: number; startBright: number; mode: 'none' | 'seek' | 'vol' | 'bright' } | null>(null);

  const effectiveSpeed = isTempSpeedActive ? 2.0 : savedSpeed;

  // Load current thumbnail image for canvas renderer + screenshot export
  useEffect(() => {
    const img = new Image();
    img.crossOrigin = 'anonymous';
    img.src = video.thumbnail;
    img.onload = () => {
      imgRef.current = img;
    };
  }, [video.thumbnail]);

  // Real-time canvas video frame simulation reacting to playback position, speed, zoom, and resize mode
  useEffect(() => {
    let raf = 0;
    const render = () => {
      const canvas = canvasRef.current;
      const ctx = canvas?.getContext('2d');
      if (canvas && ctx) {
        const w = canvas.width;
        const h = canvas.height;
        ctx.fillStyle = '#050507';
        ctx.fillRect(0, 0, w, h);

        const img = imgRef.current;
        if (img && img.naturalWidth > 0) {
          ctx.save();
          // Subtle cinematic Ken-Burns motion driven by current playback time
          const progress = currentSec / Math.max(1, video.durationSec);
          const motionScale = 1 + Math.sin(progress * Math.PI * 2) * 0.04;
          const totalScale = motionScale * zoomScale;

          const baseRatio =
            resizeMode === 'تمديد'
              ? { sw: w, sh: h }
              : resizeMode === 'تكبير'
                ? (() => {
                    const s = Math.max(w / img.naturalWidth, h / img.naturalHeight);
                    return { sw: img.naturalWidth * s, sh: img.naturalHeight * s };
                  })()
                : (() => {
                    const s = Math.min(w / img.naturalWidth, h / img.naturalHeight);
                    return { sw: img.naturalWidth * s, sh: img.naturalHeight * s };
                  })();

          const dw = baseRatio.sw * totalScale;
          const dh = baseRatio.sh * totalScale;
          const panX = Math.sin(progress * Math.PI * 4) * 10 * zoomScale;
          const panY = Math.cos(progress * Math.PI * 3) * 6 * zoomScale;

          ctx.filter = `brightness(${Math.max(25, brightness)}%)`;
          ctx.drawImage(img, (w - dw) / 2 + panX, (h - dh) / 2 + panY, dw, dh);
          ctx.restore();
        }
      }
      if (isPlaying) {
        raf = requestAnimationFrame(render);
      }
    };
    render();
    return () => {
      if (raf) cancelAnimationFrame(raf);
    };
  }, [currentSec, isPlaying, video.durationSec, video.thumbnail, zoomScale, resizeMode, brightness]);

  const showTempHud = (icon: 'seek' | 'volume' | 'brightness', text: string) => {
    setHudNotice({ icon, text });
    window.setTimeout(() => {
      setHudNotice((prev) => (prev?.text === text ? null : prev));
    }, 950);
  };

  const handleCaptureFrame = (e: React.MouseEvent) => {
    e.stopPropagation();
    if (video.isVault) {
      onToast('ممنوع التقاط لقطات الشاشة داخل الخزنة (FLAG_SECURE)');
      return;
    }
    const canvas = canvasRef.current;
    if (!canvas) return;
    try {
      const dataUrl = canvas.toDataURL('image/png');
      const stamp = formatTime(currentSec).replace(/:/g, '-');
      onFrameCaptured(dataUrl, `VideoHatak_${video.id}_${stamp}.png`);
      onToast('تم حفظ اللقطة في Pictures/لقطات فيديوهاتك');
    } catch {
      onToast('تعذر التقاط الإطار الحالي');
    }
  };

  const handleSurfacePointerDown = (e: React.PointerEvent<HTMLDivElement>) => {
    const rect = e.currentTarget.getBoundingClientRect();
    const relX = (e.clientX - rect.left) / Math.max(1, rect.width);
    gestureStartRef.current = {
      x: e.clientX,
      y: e.clientY,
      startSec: currentSec,
      startVol: volume,
      startBright: brightness,
      mode: 'none',
    };

    if (!isLocked) {
      holdTriggeredRef.current = false;
      holdTimerRef.current = window.setTimeout(() => {
        holdTriggeredRef.current = true;
        onSetTempSpeed(true);
      }, 320);
    }
    // Keep track of zone
    (e.currentTarget as HTMLDivElement).dataset.zone = relX < 0.33 ? 'left' : relX > 0.67 ? 'right' : 'center';
  };

  const handleSurfacePointerMove = (e: React.PointerEvent<HTMLDivElement>) => {
    const st = gestureStartRef.current;
    if (!st || isLocked) return;
    const dx = e.clientX - st.x;
    const dy = e.clientY - st.y;
    if (Math.abs(dx) > 14 || Math.abs(dy) > 14) {
      if (holdTimerRef.current) {
        clearTimeout(holdTimerRef.current);
        holdTimerRef.current = null;
      }
    }
    if (st.mode === 'none') {
      if (Math.abs(dx) > 18 && Math.abs(dx) > Math.abs(dy)) {
        st.mode = 'seek';
      } else if (Math.abs(dy) > 18) {
        const rect = e.currentTarget.getBoundingClientRect();
        const isRightSide = e.clientX - rect.left > rect.width / 2;
        st.mode = isRightSide ? 'vol' : 'bright';
      }
    }
    if (st.mode === 'seek') {
      const delta = Math.round(dx * 0.25);
      const next = Math.min(video.durationSec, Math.max(0, st.startSec + delta));
      onSeek(next);
      showTempHud('seek', `${delta >= 0 ? '+' : ''}${delta}ث (${formatTime(next)})`);
    } else if (st.mode === 'vol') {
      const nextVol = Math.min(100, Math.max(0, Math.round(st.startVol - dy * 0.45)));
      setVolume(nextVol);
      showTempHud('volume', `الصوت ${nextVol}%`);
    } else if (st.mode === 'bright') {
      const nextBright = Math.min(100, Math.max(15, Math.round(st.startBright - dy * 0.45)));
      setBrightness(nextBright);
      showTempHud('brightness', `السطوع ${nextBright}%`);
    }
  };

  const handleSurfacePointerUp = (e: React.PointerEvent<HTMLDivElement>) => {
    if (holdTimerRef.current) {
      clearTimeout(holdTimerRef.current);
      holdTimerRef.current = null;
    }
    if (holdTriggeredRef.current) {
      holdTriggeredRef.current = false;
      onSetTempSpeed(false);
      gestureStartRef.current = null;
      return;
    }
    const st = gestureStartRef.current;
    gestureStartRef.current = null;
    if (st && st.mode === 'seek') {
      onScrubEnd(currentSec);
      return;
    }
    if (st && st.mode !== 'none') return;

    // Single vs Double tap logic
    const zone = ((e.currentTarget as HTMLDivElement).dataset.zone as 'left' | 'center' | 'right') || 'center';
    const now = Date.now();
    const prev = lastTapRef.current;

    if (prev && now - prev.time < 280 && prev.zone === zone && !isLocked) {
      if (singleTapTimerRef.current) {
        clearTimeout(singleTapTimerRef.current);
        singleTapTimerRef.current = null;
      }
      lastTapRef.current = null;
      if (zone === 'right') {
        const next = Math.min(video.durationSec, currentSec + 10);
        onScrubEnd(next);
        showTempHud('seek', '+10 ثوانٍ');
      } else if (zone === 'left') {
        const next = Math.max(0, currentSec - 10);
        onScrubEnd(next);
        showTempHud('seek', '-10 ثوانٍ');
      } else {
        onTogglePlay();
      }
    } else {
      lastTapRef.current = { time: now, zone };
      singleTapTimerRef.current = window.setTimeout(() => {
        setControlsVisible((v) => !v);
      }, 240);
    }
  };

  const speedOptions = [0.5, 1.0, 1.25, 1.5, 2.0];
  const nextSpeed = () => {
    const idx = speedOptions.findIndex((s) => Math.abs(s - savedSpeed) < 0.05);
    const next = speedOptions[(idx + 1) % speedOptions.length];
    onSetSavedSpeed(next);
  };

  const cycleResize = () => {
    setResizeMode((r) => (r === 'ملاءمة' ? 'تكبير' : r === 'تكبير' ? 'تمديد' : 'ملاءمة'));
  };

  const activePlaybackMeta = PLAYBACK_MODES.find((m) => m.id === playbackMode) ?? PLAYBACK_MODES[2];

  const renderPlaybackModeIcon = () => {
    switch (playbackMode) {
      case 'STOP_AFTER_CURRENT':
        return <Square className="w-3.5 h-3.5" />;
      case 'SHUFFLE_ALL':
        return <Shuffle className="w-3.5 h-3.5" />;
      case 'REPEAT_ONE':
        return <Repeat1 className="w-3.5 h-3.5" />;
      case 'REPEAT_PLAYLIST':
        return <Repeat className="w-3.5 h-3.5" />;
      default:
        return <ScreenShare className="w-3.5 h-3.5" />;
    }
  };

  return (
    <div
      className="relative w-full h-full bg-black overflow-hidden select-none flex flex-col justify-between"
      onPointerDown={handleSurfacePointerDown}
      onPointerMove={handleSurfacePointerMove}
      onPointerUp={handleSurfacePointerUp}
      onPointerCancel={handleSurfacePointerUp}
    >
      {/* Live Canvas Video Surface */}
      <canvas
        ref={canvasRef}
        width={isLandscape ? 960 : 540}
        height={isLandscape ? 540 : 960}
        className="absolute inset-0 w-full h-full object-contain pointer-events-none"
      />

      {/* Top Temporary Speed 2.0x Hold Badge */}
      {isTempSpeedActive && (
        <div
          className="absolute top-14 left-1/2 -translate-x-1/2 z-40 px-3 py-1 rounded-full text-xs font-bold flex items-center gap-1.5 shadow-lg pointer-events-none"
          style={{ backgroundColor: palette.accent, color: palette.onAccent }}
        >
          <FastForward className="w-3.5 h-3.5" />
          <span>2.0x سرعة مؤقتة</span>
        </div>
      )}

      {/* Gesture Feedback HUD */}
      {hudNotice && (
        <div className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 z-40 px-4 py-2 rounded-2xl bg-black/75 border border-white/15 text-white text-xs font-bold flex items-center gap-2 pointer-events-none backdrop-blur">
          {hudNotice.icon === 'volume' && <Volume2 className="w-4 h-4" style={{ color: palette.accent }} />}
          {hudNotice.icon === 'brightness' && <Sun className="w-4 h-4" style={{ color: palette.accent }} />}
          <span>{hudNotice.text}</span>
        </div>
      )}

      {/* Vault Privacy Indicator */}
      {video.isVault && (
        <div className="absolute top-3 left-3 z-30 px-2 py-0.5 rounded bg-red-950/80 border border-red-500/40 text-red-200 text-[10px] font-semibold pointer-events-none">
          FLAG_SECURE · محمي
        </div>
      )}

      {/* Lock Screen Unlock Trigger when Locked */}
      {isLocked && controlsVisible && (
        <div className="absolute top-1/2 left-4 -translate-y-1/2 z-40">
          <button
            onClick={(e) => {
              e.stopPropagation();
              setIsLocked(false);
            }}
            className="w-11 h-11 rounded-full bg-black/75 border text-white flex items-center justify-center shadow-lg"
            style={{ borderColor: palette.accent }}
            title="فك قفل الشاشة"
          >
            <Lock className="w-5 h-5" style={{ color: palette.accent }} />
          </button>
        </div>
      )}

      {/* Top Controls Bar */}
      <div
        className={`relative z-20 transition-opacity duration-200 ${controlsVisible && !isLocked ? 'opacity-100' : 'opacity-0 pointer-events-none'}`}
        onClick={(e) => e.stopPropagation()}
        onPointerDown={(e) => e.stopPropagation()}
      >
        <div className="bg-gradient-to-b from-black/85 via-black/45 to-transparent px-3 pt-3 pb-6 flex items-center justify-between gap-2">
          <div className="flex items-center gap-1.5 min-w-0">
            <button
              onClick={onMinimize}
              className="w-9 h-9 rounded-full bg-white/10 hover:bg-white/20 text-white flex items-center justify-center shrink-0"
              title="تصغير واستمرار التشغيل في الخلفية"
            >
              <ArrowRight className="w-4 h-4" />
            </button>
            <div className="min-w-0">
              <p className="text-xs font-bold text-white truncate">{video.title}</p>
              <p className="text-[10px] text-white/65 truncate">
                {video.resolution} · {video.folderName}
              </p>
            </div>
          </div>

          <div className="flex items-center gap-1 shrink-0">
            <button
              onClick={handleCaptureFrame}
              className="w-9 h-9 rounded-full bg-white/10 hover:bg-white/20 text-white flex items-center justify-center"
              title="التقاط صورة من الفيديو"
            >
              <Camera className="w-4 h-4" />
            </button>
            <button
              onClick={onToggleLandscape}
              className="w-9 h-9 rounded-full bg-white/10 hover:bg-white/20 text-white flex items-center justify-center"
              title={isLandscape ? 'التدوير إلى عمودي' : 'التدوير إلى أفقي'}
            >
              <RotateCw className="w-4 h-4" />
            </button>
            <button
              onClick={() => setIsLocked(true)}
              className="w-9 h-9 rounded-full bg-white/10 hover:bg-white/20 text-white flex items-center justify-center"
              title="قفل الشاشة"
            >
              <Unlock className="w-4 h-4" />
            </button>
          </div>
        </div>
      </div>

      {/* Dual Subtitles Overlay */}
      {showSubtitles && (
        <div className="relative z-10 px-4 text-center space-y-1 pointer-events-none mt-auto mb-2">
          <div className="inline-block px-2.5 py-0.5 rounded bg-black/75 text-[11px] font-medium" style={{ color: palette.accentGlow }}>
            {video.subtitleEn}
          </div>
          <div>
            <span className="inline-block px-3 py-1 rounded bg-black/85 text-white text-xs font-bold shadow">
              {video.subtitleAr}
            </span>
          </div>
        </div>
      )}

      {/* Bottom Controls HUD */}
      <div
        className={`relative z-20 transition-opacity duration-200 ${controlsVisible && !isLocked ? 'opacity-100' : 'opacity-0 pointer-events-none'}`}
        onClick={(e) => e.stopPropagation()}
        onPointerDown={(e) => e.stopPropagation()}
      >
        <div className="bg-gradient-to-t from-black/95 via-black/70 to-transparent px-3 pb-3 pt-5 space-y-2">
          {/* Optional Zoom Bar */}
          {showZoomSlider && (
            <div className="flex items-center gap-2 px-2 py-1 rounded-xl bg-black/80 border border-white/10">
              <span className="text-[11px] text-white/80 whitespace-nowrap">تكبير {zoomScale.toFixed(1)}x</span>
              <input
                type="range"
                min={1}
                max={3}
                step={0.1}
                value={zoomScale}
                onChange={(e) => onSetZoomScale(parseFloat(e.target.value))}
                className="flex-1 accent-amber-400"
              />
              <button
                onClick={() => onSetZoomScale(1)}
                className="text-[10px] px-2 py-0.5 rounded bg-white/10 text-white"
              >
                إعادة
              </button>
            </div>
          )}

          {/* Timestamps + Design #4 WavySeekBar */}
          <div className="space-y-0.5">
            <div className="flex items-center justify-between text-[11px] font-mono-tabular text-white/85 px-0.5">
              <span>{formatTime(currentSec)}</span>
              <span>{formatTime(video.durationSec)}</span>
            </div>
            <WavySeekBar
              currentSec={currentSec}
              durationSec={video.durationSec}
              palette={palette}
              isPlaying={isPlaying}
              thumbnailSrc={video.thumbnail}
              onScrubChange={onSeek}
              onScrubEnd={onScrubEnd}
            />
          </div>

          {/* Primary Transport Controls */}
          <div className="flex items-center justify-between gap-1">
            <div className="flex items-center gap-1">
              <button
                onClick={onPreviousTrack}
                disabled={!hasPrevious}
                className="w-8 h-8 rounded-full flex items-center justify-center text-white disabled:opacity-35 hover:bg-white/10"
                title="الفيديو السابق"
              >
                <SkipForward className="w-4 h-4" />
              </button>
              <button
                onClick={() => onScrubEnd(Math.max(0, currentSec - 10))}
                className="w-8 h-8 rounded-full flex items-center justify-center text-white/85 hover:bg-white/10"
                title="-10 ثوانٍ"
              >
                <RotateCcw className="w-4 h-4" />
              </button>
              <button
                onClick={onTogglePlay}
                className="w-10 h-10 rounded-full flex items-center justify-center shadow-lg transition-transform active:scale-95"
                style={{ backgroundColor: palette.accent, color: palette.onAccent }}
                title={isPlaying ? 'إيقاف مؤقت' : 'تشغيل'}
              >
                {isPlaying ? <Pause className="w-5 h-5 fill-current" /> : <Play className="w-5 h-5 fill-current" />}
              </button>
              <button
                onClick={() => onScrubEnd(Math.min(video.durationSec, currentSec + 10))}
                className="w-8 h-8 rounded-full flex items-center justify-center text-white/85 hover:bg-white/10"
                title="+10 ثوانٍ"
              >
                <RotateCw className="w-4 h-4" />
              </button>
              <button
                onClick={onNextTrack}
                disabled={!hasNext}
                className="w-8 h-8 rounded-full flex items-center justify-center text-white disabled:opacity-35 hover:bg-white/10"
                title="الفيديو التالي"
              >
                <SkipBack className="w-4 h-4" />
              </button>
            </div>

            {/* Secondary Action Strip */}
            <div className="flex items-center gap-1">
              <button
                onClick={nextSpeed}
                className="px-2 py-1 rounded-lg bg-white/10 hover:bg-white/20 text-[11px] font-mono-tabular font-bold text-white"
                title="تغيير سرعة التشغيل المحفوظة"
              >
                {effectiveSpeed.toFixed(effectiveSpeed % 1 === 0 ? 1 : 2)}x
              </button>
              <button
                onClick={onCyclePlaybackMode}
                className="px-2 py-1 rounded-lg bg-white/10 hover:bg-white/20 text-[10px] font-semibold flex items-center gap-1 text-white"
                title={activePlaybackMeta.title}
              >
                <span style={{ color: palette.accent }}>{renderPlaybackModeIcon()}</span>
              </button>
              <button
                onClick={() => setShowZoomSlider((v) => !v)}
                className="w-8 h-8 rounded-lg flex items-center justify-center text-white hover:bg-white/15"
                style={showZoomSlider ? { color: palette.accent } : undefined}
                title="التكبير 1x–3x"
              >
                <ZoomIn className="w-4 h-4" />
              </button>
              <button
                onClick={cycleResize}
                className="w-8 h-8 rounded-lg flex items-center justify-center text-white hover:bg-white/15"
                title={`نسبة العرض: ${resizeMode}`}
              >
                {resizeMode === 'تكبير' ? <Maximize2 className="w-4 h-4" /> : <Minimize2 className="w-4 h-4" />}
              </button>
              <button
                onClick={() => setShowSubtitles((v) => !v)}
                className="w-8 h-8 rounded-lg flex items-center justify-center hover:bg-white/15"
                style={{ color: showSubtitles ? palette.accent : '#ffffffaa' }}
                title="تبديل الترجمة المزدوجة"
              >
                <Captions className="w-4 h-4" />
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};
