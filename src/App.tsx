import { useEffect, useMemo, useRef, useState } from 'react';
import {
  ArrowDownUp,
  ArrowRight,
  Camera,
  Check,
  ChevronLeft,
  Download,
  Edit3,
  Fingerprint,
  Folder,
  FolderInput,
  Info,
  ListMusic,
  Lock,
  MoreVertical,
  Pause,
  Play,
  Plus,
  RotateCcw,
  RotateCw,
  Search,
  Settings,
  Shield,
  Shuffle,
  Smartphone,
  Trash2,
  Video,
  X,
} from 'lucide-react';
import {
  ALL_THEMES,
  AppThemeId,
  ThemeModeSetting,
  resolvePalette,
} from './data/themes';
import {
  INITIAL_PLAYLISTS,
  INITIAL_VAULT_VIDEOS,
  INITIAL_VIDEOS,
  PLAYBACK_MODES,
  PlaybackMode,
  RESUME_MODES,
  ResumeMode,
  SortOrder,
  VirtualPlaylist,
  VirtualVideo,
  formatTime,
} from './data/initialMedia';
import { PlayerViewport } from './components/PlayerViewport';

type PhoneTab = 'videos' | 'folders' | 'playlists' | 'settings';

interface CapturedFrame {
  id: number;
  filename: string;
  dataUrl: string;
  timestamp: string;
}

export default function App() {
  // Splash & Virtual Device state
  const [showSplash, setShowSplash] = useState(true);
  const [isLandscape, setIsLandscape] = useState(false);

  // Theme state (live 8 themes + light/dark mode)
  const [themeId, setThemeId] = useState<AppThemeId>('OBSIDIAN_ELITE');
  const [themeMode, setThemeMode] = useState<ThemeModeSetting>('SYSTEM');
  const palette = useMemo(() => resolvePalette(themeId, themeMode), [themeId, themeMode]);

  // Library & Data state
  const [videos, setVideos] = useState<VirtualVideo[]>(INITIAL_VIDEOS);
  const [vaultVideos, setVaultVideos] = useState<VirtualVideo[]>(INITIAL_VAULT_VIDEOS);
  const [playlists, setPlaylists] = useState<VirtualPlaylist[]>(INITIAL_PLAYLISTS);
  const [savedPositions, setSavedPositions] = useState<Record<number, number>>({
    101: 75, // Pre-seeded 01:15 to demonstrate RESUME and ASK_EVERY_TIME immediately
  });

  // Navigation inside virtual phone
  const [activeTab, setActiveTab] = useState<PhoneTab>('videos');
  const [selectedFolder, setSelectedFolder] = useState<string | null>(null);
  const [selectedPlaylistId, setSelectedPlaylistId] = useState<number | null>(null);
  const [showVault, setShowVault] = useState(false);
  const [isVaultUnlocked, setIsVaultUnlocked] = useState(false);

  // Search & Sort
  const [searchExpanded, setSearchExpanded] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [sortOrder, setSortOrder] = useState<SortOrder>('DATE_DESC');
  const [showSortSheet, setShowSortSheet] = useState(false);

  // Playback Preferences
  const [playbackMode, setPlaybackMode] = useState<PlaybackMode>('PLAY_ALL_IN_ORDER');
  const [resumeMode, setResumeMode] = useState<ResumeMode>('RESUME');
  const [savedSpeed, setSavedSpeed] = useState(1.0);
  const [isTempSpeedActive, setIsTempSpeedActive] = useState(false);
  const [zoomScale, setZoomScale] = useState(1.0);

  // Active Player state
  const [activeQueue, setActiveQueue] = useState<VirtualVideo[]>([]);
  const [activeQueueIndex, setActiveQueueIndex] = useState(0);
  const [playerFullscreen, setPlayerFullscreen] = useState(false);
  const [isPlaying, setIsPlaying] = useState(false);
  const [currentSec, setCurrentSec] = useState(0);

  // Dialogs & Menus inside virtual phone
  const [openMenuVideoId, setOpenMenuVideoId] = useState<number | null>(null);
  const [renameTarget, setRenameTarget] = useState<VirtualVideo | null>(null);
  const [renameInput, setRenameInput] = useState('');
  const [moveTarget, setMoveTarget] = useState<VirtualVideo | null>(null);
  const [customFolderInput, setCustomFolderInput] = useState('');
  const [addToPlaylistTarget, setAddToPlaylistTarget] = useState<VirtualVideo | null>(null);
  const [infoTarget, setInfoTarget] = useState<VirtualVideo | null>(null);
  const [createPlaylistOpen, setCreatePlaylistOpen] = useState(false);
  const [newPlaylistName, setNewPlaylistName] = useState('');
  const [encryptingProgress, setEncryptingProgress] = useState<number | null>(null);
  const [resumePrompt, setResumePrompt] = useState<{
    video: VirtualVideo;
    queue: VirtualVideo[];
    index: number;
    savedSec: number;
  } | null>(null);

  const [phoneToast, setPhoneToast] = useState<string | null>(null);
  const [capturedFrames, setCapturedFrames] = useState<CapturedFrame[]>([]);

  const toastTimerRef = useRef<number | null>(null);
  const activeVideo = activeQueue[activeQueueIndex] ?? null;

  const triggerToast = (msg: string) => {
    setPhoneToast(msg);
    if (toastTimerRef.current) clearTimeout(toastTimerRef.current);
    toastTimerRef.current = window.setTimeout(() => setPhoneToast(null), 2600);
  };

  // Auto-hide Splash on mount / replay
  useEffect(() => {
    if (!showSplash) return;
    const t = window.setTimeout(() => setShowSplash(false), 1200);
    return () => clearTimeout(t);
  }, [showSplash]);

  // Real playback progression timer adhering to 5 PlaybackModes & temporary 2.0x speed
  useEffect(() => {
    if (!isPlaying || !activeVideo) return;
    const effectiveRate = isTempSpeedActive ? 2.0 : savedSpeed;
    const intervalMs = Math.max(120, Math.round(500 / effectiveRate));

    const timer = window.setInterval(() => {
      setCurrentSec((prev) => {
        const next = prev + 0.5;
        if (next >= activeVideo.durationSec) {
          // Handle track end according to active PlaybackMode
          if (playbackMode === 'REPEAT_ONE') {
            return 0;
          }
          if (playbackMode === 'STOP_AFTER_CURRENT') {
            setIsPlaying(false);
            triggerToast('توقف التشغيل تلقائيًا (إيقاف بعد الفيديو الحالي)');
            return activeVideo.durationSec;
          }
          if (playbackMode === 'SHUFFLE_ALL' && activeQueue.length > 1) {
            const others = activeQueue.map((_, i) => i).filter((i) => i !== activeQueueIndex);
            const randIdx = others[Math.floor(Math.random() * others.length)];
            setActiveQueueIndex(randIdx);
            return 0;
          }
          if (activeQueueIndex < activeQueue.length - 1) {
            setActiveQueueIndex(activeQueueIndex + 1);
            return 0;
          }
          if (playbackMode === 'REPEAT_PLAYLIST' && activeQueue.length > 0) {
            setActiveQueueIndex(0);
            return 0;
          }
          setIsPlaying(false);
          return activeVideo.durationSec;
        }
        return next;
      });
    }, intervalMs);

    return () => clearInterval(timer);
  }, [isPlaying, activeVideo, savedSpeed, isTempSpeedActive, playbackMode, activeQueue, activeQueueIndex]);

  // Persist position periodically while playing
  useEffect(() => {
    if (!activeVideo || activeVideo.isVault) return;
    setSavedPositions((prev) => ({
      ...prev,
      [activeVideo.id]: Math.floor(currentSec),
    }));
  }, [Math.floor(currentSec), activeVideo?.id]);

  // Computed sorted & filtered videos
  const displayedVideos = useMemo(() => {
    const q = searchQuery.trim().toLowerCase();
    const filtered = videos.filter((v) => {
      const folderMatch = selectedFolder ? v.folderName === selectedFolder : true;
      const queryMatch = !q || v.title.toLowerCase().includes(q) || v.folderName.toLowerCase().includes(q);
      return folderMatch && queryMatch;
    });

    return [...filtered].sort((a, b) => {
      switch (sortOrder) {
        case 'DATE_ASC':
          return a.dateAddedSec - b.dateAddedSec;
        case 'NAME_ASC':
          return a.title.localeCompare(b.title, 'ar');
        case 'NAME_DESC':
          return b.title.localeCompare(a.title, 'ar');
        case 'SIZE_DESC':
          return b.sizeMb - a.sizeMb;
        case 'DURATION_DESC':
          return b.durationSec - a.durationSec;
        case 'DATE_DESC':
        default:
          return b.dateAddedSec - a.dateAddedSec;
      }
    });
  }, [videos, selectedFolder, searchQuery, sortOrder]);

  const folders = useMemo(() => {
    const map = new Map<string, { name: string; count: number; totalMb: number; preview: string }>();
    for (const v of videos) {
      const existing = map.get(v.folderName);
      if (existing) {
        existing.count += 1;
        existing.totalMb += v.sizeMb;
      } else {
        map.set(v.folderName, { name: v.folderName, count: 1, totalMb: v.sizeMb, preview: v.thumbnail });
      }
    }
    return Array.from(map.values());
  }, [videos]);

  const selectedPlaylist = useMemo(
    () => playlists.find((p) => p.id === selectedPlaylistId) ?? null,
    [playlists, selectedPlaylistId]
  );

  // Start playing video adhering to ResumeMode
  const startVideoPlayback = (video: VirtualVideo, contextQueue: VirtualVideo[]) => {
    const queue = video.isVault ? [video] : contextQueue.length > 0 ? contextQueue : [video];
    const idx = Math.max(0, queue.findIndex((x) => x.id === video.id));
    const savedSec = savedPositions[video.id] ?? 0;

    if (resumeMode === 'ASK_EVERY_TIME' && savedSec > 3 && savedSec < video.durationSec - 3) {
      setResumePrompt({ video, queue, index: idx, savedSec });
      return;
    }

    const startPos = resumeMode === 'RESUME' && savedSec > 3 && savedSec < video.durationSec - 3 ? savedSec : 0;
    setActiveQueue(queue);
    setActiveQueueIndex(idx);
    setCurrentSec(startPos);
    setZoomScale(1.0);
    setIsTempSpeedActive(false);
    setIsPlaying(true);
    setPlayerFullscreen(true);
  };

  const resolveResumePrompt = (shouldResume: boolean) => {
    if (!resumePrompt) return;
    const { queue, index, savedSec } = resumePrompt;
    setResumePrompt(null);
    setActiveQueue(queue);
    setActiveQueueIndex(index);
    setCurrentSec(shouldResume ? savedSec : 0);
    setZoomScale(1.0);
    setIsTempSpeedActive(false);
    setIsPlaying(true);
    setPlayerFullscreen(true);
  };

  const handleCyclePlaybackMode = () => {
    const idx = PLAYBACK_MODES.findIndex((m) => m.id === playbackMode);
    const next = PLAYBACK_MODES[(idx + 1) % PLAYBACK_MODES.length];
    setPlaybackMode(next.id);
    triggerToast(`وضع التشغيل: ${next.title}`);
  };

  const handleNextTrack = () => {
    if (activeQueue.length === 0) return;
    const nextIdx =
      playbackMode === 'SHUFFLE_ALL' && activeQueue.length > 1
        ? (activeQueueIndex + 1 + Math.floor(Math.random() * (activeQueue.length - 1))) % activeQueue.length
        : activeQueueIndex + 1 < activeQueue.length
          ? activeQueueIndex + 1
          : playbackMode === 'REPEAT_PLAYLIST'
            ? 0
            : -1;
    if (nextIdx >= 0) {
      setActiveQueueIndex(nextIdx);
      setCurrentSec(0);
      setIsPlaying(true);
    }
  };

  const handlePreviousTrack = () => {
    if (activeQueue.length === 0) return;
    const prevIdx =
      activeQueueIndex - 1 >= 0
        ? activeQueueIndex - 1
        : playbackMode === 'REPEAT_PLAYLIST'
          ? activeQueue.length - 1
          : -1;
    if (prevIdx >= 0) {
      setActiveQueueIndex(prevIdx);
      setCurrentSec(0);
      setIsPlaying(true);
    }
  };

  const handleRenameSubmit = () => {
    if (!renameTarget || !renameInput.trim()) return;
    const trimmed = renameInput.trim();
    setVideos((prev) => prev.map((v) => (v.id === renameTarget.id ? { ...v, title: trimmed } : v)));
    setVaultVideos((prev) => prev.map((v) => (v.id === renameTarget.id ? { ...v, title: trimmed } : v)));
    setRenameTarget(null);
    triggerToast('تمت إعادة التسمية بنجاح');
  };

  const handleMoveToFolder = (targetFolder: string) => {
    if (!moveTarget || !targetFolder.trim()) return;
    const cleaned = targetFolder.trim();
    setVideos((prev) => prev.map((v) => (v.id === moveTarget.id ? { ...v, folderName: cleaned } : v)));
    setMoveTarget(null);
    setCustomFolderInput('');
    triggerToast(`تم النقل إلى مجلد: ${cleaned}`);
  };

  const handleImportToVault = (video: VirtualVideo) => {
    if (vaultVideos.some((v) => v.title.includes(video.title))) {
      triggerToast('هذا المقطع مشفر وموجود بالفعل في الخزنة');
      return;
    }
    setEncryptingProgress(15);
    const stepTimer = window.setInterval(() => {
      setEncryptingProgress((p) => {
        if (p === null) {
          clearInterval(stepTimer);
          return null;
        }
        if (p >= 100) {
          clearInterval(stepTimer);
          const encryptedItem: VirtualVideo = {
            ...video,
            id: Date.now(),
            folderName: 'الخزنة الخاصة',
            isVault: true,
          };
          setVaultVideos((prev) => [encryptedItem, ...prev]);
          triggerToast('تم تشفير نسخة داخل الخزنة (AES-256-GCM)');
          return null;
        }
        return p + 25;
      });
    }, 180);
  };

  const handleCreatePlaylist = () => {
    if (!newPlaylistName.trim()) return;
    const created: VirtualPlaylist = {
      id: Date.now(),
      name: newPlaylistName.trim(),
      videoIds: addToPlaylistTarget ? [addToPlaylistTarget.id] : [],
    };
    setPlaylists((prev) => [created, ...prev]);
    if (addToPlaylistTarget) {
      triggerToast(`أُضيف إلى "${created.name}"`);
      setAddToPlaylistTarget(null);
    } else {
      triggerToast(`تم إنشاء قائمة "${created.name}"`);
    }
    setNewPlaylistName('');
    setCreatePlaylistOpen(false);
  };

  const handleBackAction = () => {
    if (playerFullscreen) {
      setPlayerFullscreen(false);
      setIsLandscape(false);
      return;
    }
    if (showVault) {
      setShowVault(false);
      return;
    }
    if (selectedFolder) {
      setSelectedFolder(null);
      return;
    }
    if (selectedPlaylistId !== null) {
      setSelectedPlaylistId(null);
      return;
    }
    if (activeTab !== 'videos') {
      setActiveTab('videos');
    }
  };

  // Virtual Screen Dimensions (responds dynamically to Manual Rotation)
  const phoneDimensionsClass = isLandscape
    ? 'w-[700px] max-w-full h-[396px]'
    : 'w-[388px] max-w-full h-[780px]';

  const activePlaybackObj = PLAYBACK_MODES.find((m) => m.id === playbackMode) ?? PLAYBACK_MODES[2];
  const activeResumeObj = RESUME_MODES.find((m) => m.id === resumeMode) ?? RESUME_MODES[0];

  return (
    <div className="min-h-screen bg-[#070709] text-[#faf5eb] flex flex-col" dir="rtl">
      {/* Top Bar (3 clean zones, zero pill clutter) */}
      <header className="border-b border-white/10 bg-[#0b0b0f]/95 backdrop-blur sticky top-0 z-40">
        <div className="max-w-[1440px] mx-auto px-6 py-3.5 flex items-center justify-between gap-8">
          <div className="flex items-center gap-3 shrink-0">
            <img
              src="/app_icon.png"
              alt="أيقونة تطبيق فيديوهاتك"
              referrerPolicy="no-referrer"
              className="w-9 h-9 rounded-xl object-cover border border-amber-500/40"
            />
            <span className="text-base font-bold tracking-tight whitespace-nowrap text-[#faf5eb]">
              فيديوهاتك — كما تحب
            </span>
          </div>

          <nav className="hidden md:flex items-center gap-6 text-sm text-[#c3b8a2]">
            <button
              onClick={() => {
                setPlayerFullscreen(false);
                setIsLandscape(false);
                setShowVault(false);
                setActiveTab('videos');
              }}
              className="hover:text-[#faf5eb] transition-colors whitespace-nowrap shrink-0"
            >
              المكتبة
            </button>
            <button
              onClick={() => {
                setPlayerFullscreen(false);
                setShowVault(false);
                setActiveTab('settings');
              }}
              className="hover:text-[#faf5eb] transition-colors whitespace-nowrap shrink-0"
            >
              الثيمات الـ 8
            </button>
            <button
              onClick={() => startVideoPlayback(videos[0], videos)}
              className="hover:text-[#faf5eb] transition-colors whitespace-nowrap shrink-0"
            >
              المشغل
            </button>
            <button
              onClick={() => {
                setPlayerFullscreen(false);
                setShowVault(true);
              }}
              className="hover:text-[#faf5eb] transition-colors whitespace-nowrap shrink-0"
            >
              الخزنة الخاصة
            </button>
          </nav>

          <a
            href="/videohatak_native_android.zip"
            download="videohatak_native_android.zip"
            className="px-4 py-2 rounded-lg text-xs font-bold whitespace-nowrap shrink-0 flex items-center gap-2 transition-colors"
            style={{ backgroundColor: palette.accent, color: palette.onAccent }}
          >
            <Download className="w-4 h-4" />
            <span>تنزيل حزمة Android (ZIP)</span>
          </a>
        </div>
      </header>

      {/* Main 1440px Two-Column Stage: Virtual Android Screen + Live Interactive Guide */}
      <main className="flex-1 max-w-[1440px] w-full mx-auto px-4 md:px-6 py-6 grid grid-cols-1 lg:grid-cols-12 gap-8 items-start">
        {/* Focal Anchor: Virtual Android Phone Screen */}
        <section className="lg:col-span-7 flex flex-col items-center justify-center">
          <div className="mb-3 flex items-center gap-4 text-xs text-[#c3b8a2]">
            <span className="flex items-center gap-1.5">
              <Smartphone className="w-3.5 h-3.5" style={{ color: palette.accent }} />
              <span>الشاشة الافتراضية التفاعلية</span>
            </span>
            <span aria-hidden="true">·</span>
            <span>{isLandscape ? 'أفقي (Landscape)' : 'عمودي (Portrait)'}</span>
            <span aria-hidden="true">·</span>
            <span>{palette.displayName}</span>
          </div>

          {/* Realistic Android Device Bezel */}
          <div
            className={`relative transition-all duration-300 rounded-[36px] p-[10px] shadow-[0_30px_80px_rgba(0,0,0,0.85)] border-[2px] ${phoneDimensionsClass}`}
            style={{
              backgroundColor: '#111216',
              borderColor: palette.border,
            }}
          >
            {/* Camera Notch / Punch-hole */}
            <div className="absolute top-[16px] left-1/2 -translate-x-1/2 z-50 w-16 h-3.5 rounded-full bg-black/90 border border-white/10 pointer-events-none flex items-center justify-end px-1.5">
              <div className="w-1.5 h-1.5 rounded-full bg-[#1e293b]" />
            </div>

            {/* Virtual Screen Viewport */}
            <div
              className="relative w-full h-full rounded-[28px] overflow-hidden flex flex-col"
              style={{
                backgroundColor: palette.background,
                color: palette.onSurface,
              }}
            >
              {/* Status Bar inside Virtual Phone */}
              {!playerFullscreen && (
                <div
                  className="h-7 px-5 flex items-center justify-between text-[10px] font-mono-tabular shrink-0"
                  style={{
                    background: `linear-gradient(135deg, ${palette.topBarStart}, ${palette.topBarEnd})`,
                    color: palette.onSurfaceMuted,
                  }}
                >
                  <span>10:16</span>
                  <span>5G · 94%</span>
                </div>
              )}

              {/* 1. SPLASH SCREEN */}
              {showSplash && (
                <div
                  className="absolute inset-0 z-50 flex flex-col items-center justify-center p-6 text-center animate-fade-in"
                  style={{
                    background: 'radial-gradient(circle at center, #1d1609 0%, #070709 75%)',
                  }}
                >
                  <img
                    src="/app_icon.png"
                    alt="شعار فيديوهاتك"
                    referrerPolicy="no-referrer"
                    className="w-28 h-28 rounded-3xl shadow-2xl border border-amber-500/40 mb-5"
                  />
                  <h2 className="text-2xl font-extrabold text-[#faf5eb] tracking-tight">فيديوهاتك</h2>
                  <p className="text-sm text-[#e5b84b] mt-1 font-medium">— كما تحب —</p>
                </div>
              )}

              {/* 2. FULLSCREEN VIDEO PLAYER */}
              {playerFullscreen && activeVideo ? (
                <div className="flex-1 min-h-0 relative">
                  <PlayerViewport
                    video={activeVideo}
                    palette={palette}
                    isPlaying={isPlaying}
                    currentSec={currentSec}
                    savedSpeed={savedSpeed}
                    isTempSpeedActive={isTempSpeedActive}
                    playbackMode={playbackMode}
                    isLandscape={isLandscape}
                    hasNext={activeQueueIndex < activeQueue.length - 1 || playbackMode === 'REPEAT_PLAYLIST' || playbackMode === 'SHUFFLE_ALL'}
                    hasPrevious={activeQueueIndex > 0 || playbackMode === 'REPEAT_PLAYLIST'}
                    zoomScale={zoomScale}
                    onTogglePlay={() => setIsPlaying((p) => !p)}
                    onSeek={(sec) => setCurrentSec(sec)}
                    onScrubEnd={(sec) => {
                      setCurrentSec(sec);
                      if (!activeVideo.isVault) {
                        setSavedPositions((prev) => ({ ...prev, [activeVideo.id]: sec }));
                      }
                    }}
                    onSetSavedSpeed={(s) => {
                      setSavedSpeed(s);
                      triggerToast(`السرعة المحفوظة: ${s}x`);
                    }}
                    onSetTempSpeed={(active) => setIsTempSpeedActive(active)}
                    onCyclePlaybackMode={handleCyclePlaybackMode}
                    onNextTrack={handleNextTrack}
                    onPreviousTrack={handlePreviousTrack}
                    onToggleLandscape={() => setIsLandscape((l) => !l)}
                    onSetZoomScale={(z) => setZoomScale(z)}
                    onMinimize={() => {
                      setPlayerFullscreen(false);
                      setIsLandscape(false);
                    }}
                    onFrameCaptured={(dataUrl, filename) => {
                      setCapturedFrames((prev) => [
                        { id: Date.now(), filename, dataUrl, timestamp: formatTime(currentSec) },
                        ...prev,
                      ]);
                    }}
                    onToast={triggerToast}
                  />
                </div>
              ) : showVault ? (
                /* 3. ENCRYPTED VAULT SCREEN */
                <div className="flex-1 flex flex-col min-h-0">
                  <div
                    className="px-4 py-3 flex items-center justify-between border-b shrink-0"
                    style={{
                      background: `linear-gradient(135deg, ${palette.topBarStart}, ${palette.topBarEnd})`,
                      borderColor: palette.border,
                    }}
                  >
                    <div className="flex items-center gap-2">
                      <button
                        onClick={() => setShowVault(false)}
                        className="w-9 h-9 rounded-full flex items-center justify-center"
                        style={{ color: palette.onSurface }}
                      >
                        <ArrowRight className="w-5 h-5" />
                      </button>
                      <span className="font-bold text-sm">الخزنة الخاصة المشفرة</span>
                    </div>
                    {isVaultUnlocked && (
                      <button
                        onClick={() => setIsVaultUnlocked(false)}
                        className="text-xs font-semibold px-2.5 py-1 rounded-lg border"
                        style={{ borderColor: palette.border, color: palette.accent }}
                      >
                        قفل
                      </button>
                    )}
                  </div>

                  <div className="flex-1 overflow-y-auto phone-scroll p-4">
                    {!isVaultUnlocked ? (
                      <div className="h-full flex flex-col items-center justify-center text-center space-y-4 py-10">
                        <div
                          className="w-16 h-16 rounded-2xl flex items-center justify-center border"
                          style={{ backgroundColor: palette.surface, borderColor: palette.border, color: palette.accent }}
                        >
                          <Lock className="w-8 h-8" />
                        </div>
                        <div className="space-y-1">
                          <h3 className="font-bold text-base">الخزنة مقفلة</h3>
                          <p className="text-xs px-4" style={{ color: palette.onSurfaceMuted }}>
                            يتطلب فتح المقاطع المشفرة بنظام AES-256-GCM التحقق بالبصمة الحيوية
                          </p>
                        </div>
                        <button
                          onClick={() => {
                            setIsVaultUnlocked(true);
                            triggerToast('تم فتح الخزنة · تفعيل FLAG_SECURE');
                          }}
                          className="px-5 py-2.5 rounded-xl font-bold text-xs flex items-center gap-2 shadow"
                          style={{ backgroundColor: palette.accent, color: palette.onAccent }}
                        >
                          <Fingerprint className="w-4 h-4" />
                          <span>المصادقة بالبصمة</span>
                        </button>
                      </div>
                    ) : (
                      <div className="space-y-3">
                        <div
                          className="p-3 rounded-xl border text-xs flex items-center gap-2"
                          style={{ backgroundColor: palette.surfaceElevated, borderColor: palette.border }}
                        >
                          <Shield className="w-4 h-4 shrink-0" style={{ color: palette.accent }} />
                          <span>حماية FLAG_SECURE نشطة · التقاط الشاشة محظور</span>
                        </div>
                        {vaultVideos.map((v) => (
                          <div
                            key={v.id}
                            onClick={() => startVideoPlayback(v, [v])}
                            className="p-2.5 rounded-2xl border flex items-center gap-3 cursor-pointer"
                            style={{ backgroundColor: palette.surface, borderColor: palette.border }}
                          >
                            <div className="relative w-20 h-14 rounded-xl overflow-hidden shrink-0 bg-black">
                              <img
                                src={v.thumbnail}
                                alt={v.title}
                                referrerPolicy="no-referrer"
                                className="w-full h-full object-cover opacity-75"
                              />
                              <Lock className="w-4 h-4 absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 text-white" />
                            </div>
                            <div className="flex-1 min-w-0">
                              <p className="text-xs font-bold truncate">{v.title}</p>
                              <p className="text-[11px] font-mono-tabular mt-0.5" style={{ color: palette.onSurfaceMuted }}>
                                {formatTime(v.durationSec)} · {v.sizeMb.toFixed(1)} MB · {v.resolution}
                              </p>
                            </div>
                            <button
                              onClick={(e) => {
                                e.stopPropagation();
                                setVaultVideos((prev) => prev.filter((item) => item.id !== v.id));
                                triggerToast('تم الحذف النهائي من الخزنة');
                              }}
                              className="w-8 h-8 rounded-full flex items-center justify-center text-red-400"
                              title="حذف من الخزنة"
                            >
                              <Trash2 className="w-4 h-4" />
                            </button>
                          </div>
                        ))}
                      </div>
                    )}
                  </div>
                </div>
              ) : (
                /* 4. MAIN LIBRARY SCREEN (4 TABS) */
                <div className="flex-1 flex flex-col min-h-0">
                  {/* Top Bar */}
                  <div
                    className="px-3.5 py-2.5 border-b flex items-center justify-between gap-2 shrink-0"
                    style={{
                      background: `linear-gradient(135deg, ${palette.topBarStart}, ${palette.topBarEnd})`,
                      borderColor: palette.border,
                    }}
                  >
                    <div className="flex items-center gap-2 min-w-0">
                      {(selectedFolder || selectedPlaylist) ? (
                        <button
                          onClick={handleBackAction}
                          className="w-9 h-9 rounded-full flex items-center justify-center shrink-0"
                          style={{ color: palette.onSurface }}
                        >
                          <ArrowRight className="w-5 h-5" />
                        </button>
                      ) : (
                        <img
                          src="/app_icon.png"
                          alt="فيديوهاتك"
                          referrerPolicy="no-referrer"
                          className="w-7 h-7 rounded-lg object-cover shrink-0"
                        />
                      )}
                      <h2 className="font-bold text-sm truncate">
                        {selectedFolder
                          ? selectedFolder
                          : selectedPlaylist
                            ? selectedPlaylist.name
                            : activeTab === 'videos'
                              ? 'فيديوهاتك'
                              : activeTab === 'folders'
                                ? 'المجلدات'
                                : activeTab === 'playlists'
                                  ? 'قوائم التشغيل'
                                  : 'الإعدادات والمظهر'}
                      </h2>
                    </div>

                    <div className="flex items-center gap-0.5 shrink-0">
                      {activeTab === 'videos' && (
                        <>
                          <button
                            onClick={() => {
                              setSearchExpanded((s) => !s);
                              if (searchExpanded) setSearchQuery('');
                            }}
                            className="w-9 h-9 rounded-full flex items-center justify-center"
                            style={{ color: searchExpanded ? palette.accent : palette.onSurfaceMuted }}
                            title="بحث"
                          >
                            <Search className="w-4 h-4" />
                          </button>
                          <button
                            onClick={() => setShowSortSheet(true)}
                            className="w-9 h-9 rounded-full flex items-center justify-center"
                            style={{ color: palette.onSurfaceMuted }}
                            title="ترتيب"
                          >
                            <ArrowDownUp className="w-4 h-4" />
                          </button>
                        </>
                      )}
                      {activeTab === 'playlists' && !selectedPlaylist && (
                        <button
                          onClick={() => setCreatePlaylistOpen(true)}
                          className="w-9 h-9 rounded-full flex items-center justify-center"
                          style={{ color: palette.accent }}
                          title="قائمة جديدة"
                        >
                          <Plus className="w-5 h-5" />
                        </button>
                      )}
                      <button
                        onClick={() => setShowVault(true)}
                        className="w-9 h-9 rounded-full flex items-center justify-center"
                        style={{ color: palette.onSurfaceMuted }}
                        title="الخزنة الخاصة"
                      >
                        <Lock className="w-4 h-4" />
                      </button>
                    </div>
                  </div>

                  {/* Collapsible Compact Search Input */}
                  {searchExpanded && activeTab === 'videos' && (
                    <div className="px-3.5 py-2 border-b shrink-0" style={{ borderColor: palette.border, backgroundColor: palette.surface }}>
                      <div
                        className="flex items-center gap-2 px-3 py-1.5 rounded-xl border"
                        style={{ backgroundColor: palette.background, borderColor: palette.border }}
                      >
                        <Search className="w-3.5 h-3.5" style={{ color: palette.onSurfaceMuted }} />
                        <input
                          type="text"
                          value={searchQuery}
                          onChange={(e) => setSearchQuery(e.target.value)}
                          placeholder="ابحث عن فيديو..."
                          className="bg-transparent text-xs flex-1 outline-none"
                          style={{ color: palette.onSurface }}
                        />
                        {searchQuery && (
                          <button onClick={() => setSearchQuery('')}>
                            <X className="w-3.5 h-3.5" style={{ color: palette.onSurfaceMuted }} />
                          </button>
                        )}
                      </div>
                    </div>
                  )}

                  {/* Scrollable Tab Content */}
                  <div className="flex-1 overflow-y-auto phone-scroll p-3 space-y-2.5">
                    {/* TAB: VIDEOS (also Folder Drilldown) */}
                    {(activeTab === 'videos' || selectedFolder) && (
                      <>
                        {displayedVideos.length === 0 ? (
                          <div className="py-16 text-center text-xs" style={{ color: palette.onSurfaceMuted }}>
                            لا توجد فيديوهات مطابقة للبحث
                          </div>
                        ) : (
                          displayedVideos.map((video) => {
                            const savedSec = savedPositions[video.id] ?? 0;
                            const progressPct = Math.min(100, Math.round((savedSec / video.durationSec) * 100));
                            return (
                              <div
                                key={video.id}
                                onClick={() => startVideoPlayback(video, displayedVideos)}
                                className="relative p-2.5 rounded-2xl border flex items-center gap-3 cursor-pointer transition-colors"
                                style={{
                                  backgroundColor: palette.surface,
                                  borderColor: activeVideo?.id === video.id ? palette.accent : palette.border,
                                }}
                              >
                                <div className="relative w-24 h-14 rounded-xl overflow-hidden shrink-0 bg-black">
                                  <img
                                    src={video.thumbnail}
                                    alt={video.title}
                                    referrerPolicy="no-referrer"
                                    className="w-full h-full object-cover"
                                  />
                                  <span className="absolute bottom-1 left-1 px-1 py-0.5 rounded bg-black/75 text-white text-[9px] font-mono-tabular">
                                    {formatTime(video.durationSec)}
                                  </span>
                                  {progressPct > 2 && (
                                    <div className="absolute bottom-0 left-0 right-0 h-0.5 bg-white/20">
                                      <div className="h-full" style={{ width: `${progressPct}%`, backgroundColor: palette.accent }} />
                                    </div>
                                  )}
                                </div>

                                <div className="flex-1 min-w-0">
                                  <p className="text-xs font-bold leading-snug line-clamp-2">{video.title}</p>
                                  <p className="text-[10px] mt-1 font-mono-tabular truncate" style={{ color: palette.onSurfaceMuted }}>
                                    {video.resolution} · {video.sizeMb.toFixed(0)} MB · {video.folderName}
                                  </p>
                                </div>

                                <div className="relative shrink-0" onClick={(e) => e.stopPropagation()}>
                                  <button
                                    onClick={() => setOpenMenuVideoId(openMenuVideoId === video.id ? null : video.id)}
                                    className="w-8 h-8 rounded-full flex items-center justify-center"
                                    style={{ color: palette.onSurfaceMuted }}
                                    title="خيارات الفيديو"
                                  >
                                    <MoreVertical className="w-4 h-4" />
                                  </button>

                                  {openMenuVideoId === video.id && (
                                    <div
                                      className="absolute left-0 top-9 z-40 w-44 rounded-xl border shadow-2xl py-1 text-xs"
                                      style={{ backgroundColor: palette.surfaceElevated, borderColor: palette.border }}
                                    >
                                      <button
                                        onClick={() => {
                                          setOpenMenuVideoId(null);
                                          setAddToPlaylistTarget(video);
                                        }}
                                        className="w-full px-3 py-2 flex items-center gap-2 hover:opacity-80 text-right"
                                      >
                                        <ListMusic className="w-3.5 h-3.5" style={{ color: palette.accent }} />
                                        <span>إضافة إلى القائمة</span>
                                      </button>
                                      <button
                                        onClick={() => {
                                          setOpenMenuVideoId(null);
                                          setRenameTarget(video);
                                          setRenameInput(video.title);
                                        }}
                                        className="w-full px-3 py-2 flex items-center gap-2 hover:opacity-80 text-right"
                                      >
                                        <Edit3 className="w-3.5 h-3.5" style={{ color: palette.accent }} />
                                        <span>تغيير الاسم</span>
                                      </button>
                                      <button
                                        onClick={() => {
                                          setOpenMenuVideoId(null);
                                          setMoveTarget(video);
                                        }}
                                        className="w-full px-3 py-2 flex items-center gap-2 hover:opacity-80 text-right"
                                      >
                                        <FolderInput className="w-3.5 h-3.5" style={{ color: palette.accent }} />
                                        <span>نقل إلى مجلد</span>
                                      </button>
                                      <button
                                        onClick={() => {
                                          setOpenMenuVideoId(null);
                                          handleImportToVault(video);
                                        }}
                                        className="w-full px-3 py-2 flex items-center gap-2 hover:opacity-80 text-right"
                                      >
                                        <Lock className="w-3.5 h-3.5" style={{ color: palette.accent }} />
                                        <span>إضافة إلى الخزنة</span>
                                      </button>
                                      <button
                                        onClick={() => {
                                          setOpenMenuVideoId(null);
                                          setInfoTarget(video);
                                        }}
                                        className="w-full px-3 py-2 flex items-center gap-2 hover:opacity-80 text-right"
                                      >
                                        <Info className="w-3.5 h-3.5" style={{ color: palette.accent }} />
                                        <span>معلومات الفيديو</span>
                                      </button>
                                    </div>
                                  )}
                                </div>
                              </div>
                            );
                          })
                        )}
                      </>
                    )}

                    {/* TAB: FOLDERS */}
                    {activeTab === 'folders' && !selectedFolder && (
                      <div className="space-y-2.5">
                        {folders.map((folder) => (
                          <div
                            key={folder.name}
                            onClick={() => setSelectedFolder(folder.name)}
                            className="p-3 rounded-2xl border flex items-center justify-between gap-3 cursor-pointer"
                            style={{ backgroundColor: palette.surface, borderColor: palette.border }}
                          >
                            <div className="flex items-center gap-3 min-w-0">
                              <div
                                className="w-11 h-11 rounded-xl flex items-center justify-center shrink-0"
                                style={{ backgroundColor: palette.surfaceElevated, color: palette.accent }}
                              >
                                <Folder className="w-5 h-5" />
                              </div>
                              <div className="min-w-0">
                                <p className="text-xs font-bold truncate">{folder.name}</p>
                                <p className="text-[11px] font-mono-tabular mt-0.5" style={{ color: palette.onSurfaceMuted }}>
                                  {folder.count} فيديو · {folder.totalMb.toFixed(0)} MB
                                </p>
                              </div>
                            </div>
                            <ChevronLeft className="w-4 h-4 shrink-0" style={{ color: palette.onSurfaceMuted }} />
                          </div>
                        ))}
                      </div>
                    )}

                    {/* TAB: PLAYLISTS */}
                    {activeTab === 'playlists' && !selectedPlaylist && (
                      <div className="space-y-2.5">
                        {playlists.map((pl) => (
                          <div
                            key={pl.id}
                            onClick={() => setSelectedPlaylistId(pl.id)}
                            className="p-3 rounded-2xl border flex items-center justify-between gap-3 cursor-pointer"
                            style={{ backgroundColor: palette.surface, borderColor: palette.border }}
                          >
                            <div className="flex items-center gap-3 min-w-0">
                              <div
                                className="w-11 h-11 rounded-xl flex items-center justify-center shrink-0"
                                style={{ backgroundColor: palette.surfaceElevated, color: palette.accent }}
                              >
                                <ListMusic className="w-5 h-5" />
                              </div>
                              <div className="min-w-0">
                                <p className="text-xs font-bold truncate">{pl.name}</p>
                                <p className="text-[11px] mt-0.5" style={{ color: palette.onSurfaceMuted }}>
                                  {pl.videoIds.length} فيديو
                                </p>
                              </div>
                            </div>
                            <button
                              onClick={(e) => {
                                e.stopPropagation();
                                setPlaylists((prev) => prev.filter((item) => item.id !== pl.id));
                                triggerToast('تم حذف القائمة');
                              }}
                              className="w-8 h-8 rounded-full flex items-center justify-center"
                              style={{ color: palette.onSurfaceMuted }}
                              title="حذف القائمة"
                            >
                              <Trash2 className="w-4 h-4" />
                            </button>
                          </div>
                        ))}
                      </div>
                    )}

                    {/* PLAYLIST DETAIL VIEW */}
                    {activeTab === 'playlists' && selectedPlaylist && (
                      <div className="space-y-3">
                        <div className="flex items-center gap-2">
                          <button
                            onClick={() => {
                              const items = selectedPlaylist.videoIds
                                .map((id) => videos.find((v) => v.id === id))
                                .filter((v): v is VirtualVideo => Boolean(v));
                              if (items.length > 0) startVideoPlayback(items[0], items);
                            }}
                            className="flex-1 py-2 rounded-xl text-xs font-bold flex items-center justify-center gap-1.5"
                            style={{ backgroundColor: palette.accent, color: palette.onAccent }}
                          >
                            <Play className="w-3.5 h-3.5 fill-current" />
                            <span>تشغيل الكل</span>
                          </button>
                          <button
                            onClick={() => {
                              setPlaybackMode('SHUFFLE_ALL');
                              const items = selectedPlaylist.videoIds
                                .map((id) => videos.find((v) => v.id === id))
                                .filter((v): v is VirtualVideo => Boolean(v));
                              if (items.length > 0) startVideoPlayback(items[0], items);
                            }}
                            className="px-3 py-2 rounded-xl border text-xs font-semibold flex items-center gap-1"
                            style={{ borderColor: palette.border, backgroundColor: palette.surface }}
                          >
                            <Shuffle className="w-3.5 h-3.5" style={{ color: palette.accent }} />
                            <span>خلط</span>
                          </button>
                        </div>

                        {selectedPlaylist.videoIds
                          .map((id) => videos.find((v) => v.id === id))
                          .filter((v): v is VirtualVideo => Boolean(v))
                          .map((v) => (
                            <div
                              key={v.id}
                              onClick={() => {
                                const items = selectedPlaylist.videoIds
                                  .map((id) => videos.find((x) => x.id === id))
                                  .filter((x): x is VirtualVideo => Boolean(x));
                                startVideoPlayback(v, items);
                              }}
                              className="p-2 rounded-xl border flex items-center justify-between gap-2 cursor-pointer"
                              style={{ backgroundColor: palette.surface, borderColor: palette.border }}
                            >
                              <div className="flex items-center gap-2.5 min-w-0">
                                <img
                                  src={v.thumbnail}
                                  alt={v.title}
                                  referrerPolicy="no-referrer"
                                  className="w-16 h-10 rounded-lg object-cover shrink-0"
                                />
                                <p className="text-xs font-semibold truncate">{v.title}</p>
                              </div>
                              <button
                                onClick={(e) => {
                                  e.stopPropagation();
                                  setPlaylists((prev) =>
                                    prev.map((p) =>
                                      p.id === selectedPlaylist.id
                                        ? { ...p, videoIds: p.videoIds.filter((id) => id !== v.id) }
                                        : p
                                    )
                                  );
                                }}
                                className="w-8 h-8 rounded-full flex items-center justify-center shrink-0"
                                style={{ color: palette.onSurfaceMuted }}
                              >
                                <X className="w-3.5 h-3.5" />
                              </button>
                            </div>
                          ))}
                      </div>
                    )}

                    {/* TAB: SETTINGS (8 THEMES + LIGHT/DARK + 5 PLAYBACK MODES + 3 RESUME MODES + VAULT) */}
                    {activeTab === 'settings' && (
                      <div className="space-y-4 pb-3">
                        {/* 8 Themes Selector */}
                        <div className="space-y-2">
                          <h3 className="text-xs font-bold">المظاهر الثمانية المعتمدة</h3>
                          <div className="grid grid-cols-2 gap-2">
                            {ALL_THEMES.map((t) => {
                              const swatch = resolvePalette(t.id, t.isLightByDefault ? 'LIGHT' : 'DARK');
                              const isSelected = themeId === t.id;
                              return (
                                <button
                                  key={t.id}
                                  onClick={() => {
                                    setThemeId(t.id);
                                    setThemeMode(t.isLightByDefault ? 'LIGHT' : 'DARK');
                                  }}
                                  className="p-2.5 rounded-xl border text-right transition-all"
                                  style={{
                                    backgroundColor: swatch.surface,
                                    borderColor: isSelected ? swatch.accent : swatch.border,
                                    color: swatch.onSurface,
                                  }}
                                >
                                  <div className="flex items-center justify-between gap-1">
                                    <span className="text-[11px] font-bold truncate">{t.displayName}</span>
                                    <span
                                      className="w-3 h-3 rounded-full shrink-0"
                                      style={{ backgroundColor: swatch.accent }}
                                    />
                                  </div>
                                  <p className="text-[10px] mt-0.5 truncate" style={{ color: swatch.onSurfaceMuted }}>
                                    {t.arabicTagline}
                                  </p>
                                </button>
                              );
                            })}
                          </div>
                        </div>

                        {/* Dark / Light Mode Segmented */}
                        <div className="space-y-1.5">
                          <h3 className="text-xs font-bold">وضع السطوع</h3>
                          <div
                            className="grid grid-cols-3 gap-1 p-1 rounded-xl border"
                            style={{ backgroundColor: palette.surface, borderColor: palette.border }}
                          >
                            {(['SYSTEM', 'DARK', 'LIGHT'] as ThemeModeSetting[]).map((mode) => (
                              <button
                                key={mode}
                                onClick={() => setThemeMode(mode)}
                                className="py-1.5 rounded-lg text-[11px] font-semibold transition-colors"
                                style={
                                  themeMode === mode
                                    ? { backgroundColor: palette.accent, color: palette.onAccent }
                                    : { color: palette.onSurfaceMuted }
                                }
                              >
                                {mode === 'SYSTEM' ? 'تلقائي' : mode === 'DARK' ? 'داكن' : 'فاتح'}
                              </button>
                            ))}
                          </div>
                        </div>

                        {/* 5 Playback Modes */}
                        <div className="space-y-1.5">
                          <h3 className="text-xs font-bold">أوضاع التشغيل</h3>
                          <div
                            className="rounded-xl border divide-y overflow-hidden"
                            style={{ backgroundColor: palette.surface, borderColor: palette.border }}
                          >
                            {PLAYBACK_MODES.map((m) => (
                              <button
                                key={m.id}
                                onClick={() => setPlaybackMode(m.id)}
                                className="w-full px-3 py-2 flex items-center justify-between text-right"
                                style={{ borderColor: palette.border }}
                              >
                                <div className="min-w-0">
                                  <p className="text-[11px] font-bold">{m.title}</p>
                                  <p className="text-[10px] truncate" style={{ color: palette.onSurfaceMuted }}>
                                    {m.description}
                                  </p>
                                </div>
                                {playbackMode === m.id && (
                                  <Check className="w-4 h-4 shrink-0" style={{ color: palette.accent }} />
                                )}
                              </button>
                            ))}
                          </div>
                        </div>

                        {/* 3 Resume Modes */}
                        <div className="space-y-1.5">
                          <h3 className="text-xs font-bold">سياسة استئناف التشغيل</h3>
                          <div
                            className="rounded-xl border divide-y overflow-hidden"
                            style={{ backgroundColor: palette.surface, borderColor: palette.border }}
                          >
                            {RESUME_MODES.map((r) => (
                              <button
                                key={r.id}
                                onClick={() => setResumeMode(r.id)}
                                className="w-full px-3 py-2 flex items-center justify-between text-right"
                                style={{ borderColor: palette.border }}
                              >
                                <div className="min-w-0">
                                  <p className="text-[11px] font-bold">{r.title}</p>
                                  <p className="text-[10px] truncate" style={{ color: palette.onSurfaceMuted }}>
                                    {r.description}
                                  </p>
                                </div>
                                {resumeMode === r.id && (
                                  <Check className="w-4 h-4 shrink-0" style={{ color: palette.accent }} />
                                )}
                              </button>
                            ))}
                          </div>
                        </div>

                        {/* Vault Entry inside Settings */}
                        <button
                          onClick={() => setShowVault(true)}
                          className="w-full p-3 rounded-xl border flex items-center justify-between"
                          style={{ backgroundColor: palette.surface, borderColor: palette.border }}
                        >
                          <div className="flex items-center gap-2.5">
                            <Lock className="w-4 h-4" style={{ color: palette.accent }} />
                            <div className="text-right">
                              <p className="text-xs font-bold">الخزنة الخاصة المشفرة</p>
                              <p className="text-[10px]" style={{ color: palette.onSurfaceMuted }}>
                                {vaultVideos.length} مقطع مشفر بـ AES-256-GCM
                              </p>
                            </div>
                          </div>
                          <ChevronLeft className="w-4 h-4" style={{ color: palette.onSurfaceMuted }} />
                        </button>
                      </div>
                    )}
                  </div>

                  {/* MiniPlayerBar (keeps playing cleanly when minimized!) */}
                  {activeVideo && !playerFullscreen && (
                    <div
                      onClick={() => setPlayerFullscreen(true)}
                      className="mx-2 mb-1.5 rounded-xl border overflow-hidden cursor-pointer shrink-0 shadow-lg"
                      style={{ backgroundColor: palette.surfaceElevated, borderColor: palette.accent }}
                    >
                      <div className="h-0.5 w-full" style={{ backgroundColor: palette.seekBarUnplayed }}>
                        <div
                          className="h-full"
                          style={{
                            width: `${Math.min(100, (currentSec / Math.max(1, activeVideo.durationSec)) * 100)}%`,
                            backgroundColor: palette.accent,
                          }}
                        />
                      </div>
                      <div className="px-2.5 py-1.5 flex items-center justify-between gap-2">
                        <div className="flex items-center gap-2 min-w-0">
                          <img
                            src={activeVideo.thumbnail}
                            alt={activeVideo.title}
                            referrerPolicy="no-referrer"
                            className="w-9 h-7 rounded object-cover shrink-0"
                          />
                          <div className="min-w-0">
                            <p className="text-[11px] font-bold truncate">{activeVideo.title}</p>
                            <p className="text-[9px] font-mono-tabular" style={{ color: palette.onSurfaceMuted }}>
                              {formatTime(currentSec)} / {formatTime(activeVideo.durationSec)}
                            </p>
                          </div>
                        </div>
                        <div className="flex items-center gap-1 shrink-0" onClick={(e) => e.stopPropagation()}>
                          <button
                            onClick={() => setIsPlaying((p) => !p)}
                            className="w-8 h-8 rounded-full flex items-center justify-center"
                            style={{ color: palette.accent }}
                          >
                            {isPlaying ? <Pause className="w-4 h-4 fill-current" /> : <Play className="w-4 h-4 fill-current" />}
                          </button>
                          <button
                            onClick={() => {
                              setIsPlaying(false);
                              setActiveQueue([]);
                            }}
                            className="w-8 h-8 rounded-full flex items-center justify-center"
                            style={{ color: palette.onSurfaceMuted }}
                          >
                            <X className="w-3.5 h-3.5" />
                          </button>
                        </div>
                      </div>
                    </div>
                  )}

                  {/* 4-Tab Bottom Navigation Bar */}
                  <nav
                    className="grid grid-cols-4 border-t px-1 py-1.5 shrink-0"
                    style={{ backgroundColor: palette.surface, borderColor: palette.border }}
                  >
                    {(
                      [
                        { id: 'videos', label: 'الفيديوهات', icon: Video },
                        { id: 'folders', label: 'المجلدات', icon: Folder },
                        { id: 'playlists', label: 'القوائم', icon: ListMusic },
                        { id: 'settings', label: 'الإعدادات', icon: Settings },
                      ] as const
                    ).map((item) => {
                      const IconComp = item.icon;
                      const isActive = activeTab === item.id;
                      return (
                        <button
                          key={item.id}
                          onClick={() => {
                            setActiveTab(item.id);
                            setSelectedFolder(null);
                            setSelectedPlaylistId(null);
                          }}
                          className="flex flex-col items-center justify-center py-1 rounded-xl transition-colors"
                          style={{ color: isActive ? palette.accent : palette.onSurfaceMuted }}
                        >
                          <IconComp className="w-4 h-4" />
                          <span className="text-[10px] font-semibold mt-0.5 whitespace-nowrap">{item.label}</span>
                        </button>
                      );
                    })}
                  </nav>
                </div>
              )}

              {/* INTERNAL DIALOGS & SHEETS INSIDE VIRTUAL PHONE */}
              {/* Sort Bottom Sheet */}
              {showSortSheet && (
                <div
                  onClick={() => setShowSortSheet(false)}
                  className="absolute inset-0 z-40 bg-black/60 flex items-end"
                >
                  <div
                    onClick={(e) => e.stopPropagation()}
                    className="w-full rounded-t-3xl p-4 space-y-2 border-t"
                    style={{ backgroundColor: palette.surfaceElevated, borderColor: palette.border }}
                  >
                    <div className="w-10 h-1 rounded-full mx-auto mb-2" style={{ backgroundColor: palette.border }} />
                    <h4 className="text-xs font-bold mb-1">ترتيب الفيديوهات</h4>
                    {(
                      [
                        { id: 'DATE_DESC', label: 'الأحدث أولًا' },
                        { id: 'DATE_ASC', label: 'الأقدم أولًا' },
                        { id: 'NAME_ASC', label: 'الاسم (أ - ي)' },
                        { id: 'NAME_DESC', label: 'الاسم (ي - أ)' },
                        { id: 'SIZE_DESC', label: 'الحجم (الأكبر أولًا)' },
                        { id: 'DURATION_DESC', label: 'المدة (الأطول أولًا)' },
                      ] as Array<{ id: SortOrder; label: string }>
                    ).map((opt) => (
                      <button
                        key={opt.id}
                        onClick={() => {
                          setSortOrder(opt.id);
                          setShowSortSheet(false);
                        }}
                        className="w-full py-2 px-2 rounded-lg flex items-center justify-between text-xs"
                      >
                        <span style={{ color: sortOrder === opt.id ? palette.accent : palette.onSurface }}>
                          {opt.label}
                        </span>
                        {sortOrder === opt.id && <Check className="w-4 h-4" style={{ color: palette.accent }} />}
                      </button>
                    ))}
                  </div>
                </div>
              )}

              {/* Rename Dialog */}
              {renameTarget && (
                <div className="absolute inset-0 z-50 bg-black/65 flex items-center justify-center p-4">
                  <div
                    className="w-full rounded-2xl border p-4 space-y-3"
                    style={{ backgroundColor: palette.surfaceElevated, borderColor: palette.border }}
                  >
                    <h4 className="text-xs font-bold">إعادة تسمية الفيديو</h4>
                    <input
                      type="text"
                      value={renameInput}
                      onChange={(e) => setRenameInput(e.target.value)}
                      className="w-full px-3 py-2 rounded-xl border text-xs bg-transparent outline-none"
                      style={{ borderColor: palette.border, color: palette.onSurface }}
                    />
                    <div className="flex items-center justify-end gap-2">
                      <button onClick={() => setRenameTarget(null)} className="px-3 py-1.5 text-xs" style={{ color: palette.onSurfaceMuted }}>
                        إلغاء
                      </button>
                      <button
                        onClick={handleRenameSubmit}
                        className="px-4 py-1.5 rounded-lg text-xs font-bold"
                        style={{ backgroundColor: palette.accent, color: palette.onAccent }}
                      >
                        حفظ
                      </button>
                    </div>
                  </div>
                </div>
              )}

              {/* Move To Folder Dialog */}
              {moveTarget && (
                <div className="absolute inset-0 z-50 bg-black/65 flex items-center justify-center p-4">
                  <div
                    className="w-full rounded-2xl border p-4 space-y-2.5"
                    style={{ backgroundColor: palette.surfaceElevated, borderColor: palette.border }}
                  >
                    <h4 className="text-xs font-bold">نقل إلى مجلد</h4>
                    <div className="space-y-1 max-h-36 overflow-y-auto phone-scroll">
                      {folders.map((f) => (
                        <button
                          key={f.name}
                          onClick={() => handleMoveToFolder(f.name)}
                          className="w-full px-3 py-2 rounded-xl border text-right text-xs flex items-center justify-between"
                          style={{ backgroundColor: palette.surface, borderColor: palette.border }}
                        >
                          <span>{f.name}</span>
                          {moveTarget.folderName === f.name && <Check className="w-3.5 h-3.5" style={{ color: palette.accent }} />}
                        </button>
                      ))}
                    </div>
                    <div className="flex items-center gap-1.5 pt-1">
                      <input
                        type="text"
                        value={customFolderInput}
                        onChange={(e) => setCustomFolderInput(e.target.value)}
                        placeholder="أو اسم مجلد جديد..."
                        className="flex-1 px-2.5 py-1.5 rounded-lg border text-xs bg-transparent outline-none"
                        style={{ borderColor: palette.border, color: palette.onSurface }}
                      />
                      <button
                        onClick={() => handleMoveToFolder(customFolderInput)}
                        className="px-3 py-1.5 rounded-lg text-xs font-bold"
                        style={{ backgroundColor: palette.accent, color: palette.onAccent }}
                      >
                        نقل
                      </button>
                    </div>
                    <button onClick={() => setMoveTarget(null)} className="w-full text-center text-xs pt-1" style={{ color: palette.onSurfaceMuted }}>
                      إغلاق
                    </button>
                  </div>
                </div>
              )}

              {/* Add to Playlist Dialog */}
              {addToPlaylistTarget && (
                <div className="absolute inset-0 z-50 bg-black/65 flex items-center justify-center p-4">
                  <div
                    className="w-full rounded-2xl border p-4 space-y-2.5"
                    style={{ backgroundColor: palette.surfaceElevated, borderColor: palette.border }}
                  >
                    <h4 className="text-xs font-bold">إضافة إلى قائمة تشغيل</h4>
                    <button
                      onClick={() => setCreatePlaylistOpen(true)}
                      className="w-full py-2 rounded-xl text-xs font-bold flex items-center justify-center gap-1.5"
                      style={{ backgroundColor: palette.accent, color: palette.onAccent }}
                    >
                      <Plus className="w-3.5 h-3.5" />
                      <span>قائمة جديدة</span>
                    </button>
                    <div className="space-y-1 max-h-40 overflow-y-auto phone-scroll">
                      {playlists.map((pl) => (
                        <button
                          key={pl.id}
                          onClick={() => {
                            setPlaylists((prev) =>
                              prev.map((p) =>
                                p.id === pl.id && !p.videoIds.includes(addToPlaylistTarget.id)
                                  ? { ...p, videoIds: [...p.videoIds, addToPlaylistTarget.id] }
                                  : p
                              )
                            );
                            triggerToast(`أُضيف إلى "${pl.name}"`);
                            setAddToPlaylistTarget(null);
                          }}
                          className="w-full px-3 py-2 rounded-xl border text-right text-xs flex items-center justify-between"
                          style={{ backgroundColor: palette.surface, borderColor: palette.border }}
                        >
                          <span>{pl.name}</span>
                          <span style={{ color: palette.onSurfaceMuted }}>{pl.videoIds.length}</span>
                        </button>
                      ))}
                    </div>
                    <button onClick={() => setAddToPlaylistTarget(null)} className="w-full text-center text-xs pt-1" style={{ color: palette.onSurfaceMuted }}>
                      إغلاق
                    </button>
                  </div>
                </div>
              )}

              {/* Create Playlist Dialog */}
              {createPlaylistOpen && (
                <div className="absolute inset-0 z-50 bg-black/70 flex items-center justify-center p-4">
                  <div
                    className="w-full rounded-2xl border p-4 space-y-3"
                    style={{ backgroundColor: palette.surfaceElevated, borderColor: palette.border }}
                  >
                    <h4 className="text-xs font-bold">إنشاء قائمة تشغيل</h4>
                    <input
                      type="text"
                      value={newPlaylistName}
                      onChange={(e) => setNewPlaylistName(e.target.value)}
                      placeholder="اسم القائمة..."
                      className="w-full px-3 py-2 rounded-xl border text-xs bg-transparent outline-none"
                      style={{ borderColor: palette.border, color: palette.onSurface }}
                    />
                    <div className="flex items-center justify-end gap-2">
                      <button onClick={() => setCreatePlaylistOpen(false)} className="px-3 py-1.5 text-xs" style={{ color: palette.onSurfaceMuted }}>
                        إلغاء
                      </button>
                      <button
                        onClick={handleCreatePlaylist}
                        className="px-4 py-1.5 rounded-lg text-xs font-bold"
                        style={{ backgroundColor: palette.accent, color: palette.onAccent }}
                      >
                        إنشاء
                      </button>
                    </div>
                  </div>
                </div>
              )}

              {/* Video Info Dialog */}
              {infoTarget && (
                <div className="absolute inset-0 z-50 bg-black/65 flex items-center justify-center p-4">
                  <div
                    className="w-full rounded-2xl border p-4 space-y-2 text-xs"
                    style={{ backgroundColor: palette.surfaceElevated, borderColor: palette.border }}
                  >
                    <h4 className="font-bold text-sm">معلومات الفيديو</h4>
                    <div className="space-y-1.5 pt-1" style={{ color: palette.onSurfaceMuted }}>
                      <p>العنوان: <span style={{ color: palette.onSurface }}>{infoTarget.title}</span></p>
                      <p>المجلد: <span style={{ color: palette.onSurface }}>{infoTarget.folderName}</span></p>
                      <p className="font-mono-tabular">الأبعاد: <span style={{ color: palette.onSurface }}>{infoTarget.width}×{infoTarget.height} ({infoTarget.resolution})</span></p>
                      <p className="font-mono-tabular">المدة: <span style={{ color: palette.onSurface }}>{formatTime(infoTarget.durationSec)}</span></p>
                      <p className="font-mono-tabular">الحجم: <span style={{ color: palette.onSurface }}>{infoTarget.sizeMb.toFixed(1)} MB</span></p>
                    </div>
                    <button
                      onClick={() => setInfoTarget(null)}
                      className="w-full mt-2 py-2 rounded-xl text-xs font-bold"
                      style={{ backgroundColor: palette.accent, color: palette.onAccent }}
                    >
                      حسناً
                    </button>
                  </div>
                </div>
              )}

              {/* Encryption Progress Modal */}
              {encryptingProgress !== null && (
                <div className="absolute inset-0 z-50 bg-black/70 flex items-center justify-center p-5">
                  <div
                    className="w-full rounded-2xl border p-4 space-y-3"
                    style={{ backgroundColor: palette.surfaceElevated, borderColor: palette.border }}
                  >
                    <h4 className="text-xs font-bold">جارٍ التشفير وحفظ نسخة في الخزنة...</h4>
                    <div className="h-2 rounded-full overflow-hidden bg-black/40">
                      <div
                        className="h-full transition-all duration-150"
                        style={{ width: `${encryptingProgress}%`, backgroundColor: palette.accent }}
                      />
                    </div>
                    <p className="text-[11px] font-mono-tabular text-center" style={{ color: palette.onSurfaceMuted }}>
                      AES-256-GCM · {encryptingProgress}%
                    </p>
                  </div>
                </div>
              )}

              {/* ASK_EVERY_TIME Resume Prompt */}
              {resumePrompt && (
                <div className="absolute inset-0 z-50 bg-black/70 flex items-center justify-center p-4">
                  <div
                    className="w-full rounded-2xl border p-4 space-y-3"
                    style={{ backgroundColor: palette.surfaceElevated, borderColor: palette.border }}
                  >
                    <h4 className="text-xs font-bold">استئناف المشاهدة؟</h4>
                    <p className="text-[11px] leading-relaxed" style={{ color: palette.onSurfaceMuted }}>
                      سبق وتوقفت عند <span className="font-mono-tabular font-bold" style={{ color: palette.accent }}>{formatTime(resumePrompt.savedSec)}</span> في «{resumePrompt.video.title}».
                    </p>
                    <div className="flex items-center gap-2 pt-1">
                      <button
                        onClick={() => resolveResumePrompt(false)}
                        className="flex-1 py-2 rounded-xl border text-xs font-semibold"
                        style={{ borderColor: palette.border }}
                      >
                        من البداية
                      </button>
                      <button
                        onClick={() => resolveResumePrompt(true)}
                        className="flex-1 py-2 rounded-xl text-xs font-bold"
                        style={{ backgroundColor: palette.accent, color: palette.onAccent }}
                      >
                        استئناف
                      </button>
                    </div>
                  </div>
                </div>
              )}

              {/* Toast Banner inside Phone */}
              {phoneToast && (
                <div
                  className="absolute bottom-16 left-1/2 -translate-x-1/2 z-50 px-3.5 py-1.5 rounded-xl text-[11px] font-semibold shadow-2xl border whitespace-nowrap pointer-events-none"
                  style={{
                    backgroundColor: palette.surfaceElevated,
                    borderColor: palette.accent,
                    color: palette.onSurface,
                  }}
                >
                  {phoneToast}
                </div>
              )}
            </div>
          </div>
        </section>

        {/* Right Column: Quick Interactive Showcase Controls & Live Synced State */}
        <aside className="lg:col-span-5 space-y-6">
          <div className="space-y-2">
            <h1 className="text-2xl font-extrabold tracking-tight" style={{ textWrap: 'balance' }}>
              تجربة التطبيق الكاملة داخل الشاشة الافتراضية
            </h1>
            <p className="text-sm text-[#c3b8a2] leading-relaxed">
              تحكّم مباشرة في المشغل والمكتبة والخزنة والثيمات الـ 8 من داخل شاشة الهاتف الافتراضية، أو استخدم أزرار الاختبار السريع أدناه للانتقال الفوري لأي ميزة.
            </p>
          </div>

          {/* Live 8-Theme Instant Switcher */}
          <div className="pt-4 border-t border-white/10 space-y-3">
            <div className="flex items-center justify-between">
              <h2 className="text-sm font-bold text-[#faf5eb]">تبديل مباشر بين الثيمات الـ 8</h2>
              <span className="text-xs text-[#c3b8a2]">{palette.displayName}</span>
            </div>
            <div className="grid grid-cols-2 sm:grid-cols-4 lg:grid-cols-2 gap-2">
              {ALL_THEMES.map((t) => {
                const p = resolvePalette(t.id, t.isLightByDefault ? 'LIGHT' : 'DARK');
                const selected = themeId === t.id;
                return (
                  <button
                    key={t.id}
                    onClick={() => {
                      setThemeId(t.id);
                      setThemeMode(t.isLightByDefault ? 'LIGHT' : 'DARK');
                    }}
                    className="px-3 py-2 rounded-xl border text-right flex items-center justify-between gap-2 transition-colors"
                    style={{
                      backgroundColor: selected ? p.surfaceElevated : '#121216',
                      borderColor: selected ? p.accent : 'rgba(255,255,255,0.08)',
                    }}
                  >
                    <div className="min-w-0">
                      <p className="text-xs font-bold truncate" style={{ color: selected ? p.onSurface : '#faf5eb' }}>
                        {t.displayName}
                      </p>
                      <p className="text-[11px] truncate" style={{ color: selected ? p.onSurfaceMuted : '#c3b8a2' }}>
                        {t.arabicTagline}
                      </p>
                    </div>
                    <span className="w-3 h-3 rounded-full shrink-0" style={{ backgroundColor: p.accent }} />
                  </button>
                );
              })}
            </div>
          </div>

          {/* One-Click Feature Scenario Triggers */}
          <div className="pt-4 border-t border-white/10 space-y-3">
            <h2 className="text-sm font-bold text-[#faf5eb]">اختبار سريع للميزات الجديدة</h2>
            <div className="grid grid-cols-2 gap-2">
              <button
                onClick={() => setShowSplash(true)}
                className="px-3 py-2.5 rounded-xl bg-[#121216] hover:bg-[#1a1a22] border border-white/10 text-xs font-semibold flex items-center gap-2"
              >
                <RotateCcw className="w-3.5 h-3.5" style={{ color: palette.accent }} />
                <span className="truncate">إعادة شاشة البداية</span>
              </button>
              <button
                onClick={() => setIsLandscape((l) => !l)}
                className="px-3 py-2.5 rounded-xl bg-[#121216] hover:bg-[#1a1a22] border border-white/10 text-xs font-semibold flex items-center gap-2"
              >
                <RotateCw className="w-3.5 h-3.5" style={{ color: palette.accent }} />
                <span className="truncate">تدوير الشاشة الافتراضية</span>
              </button>
              <button
                onClick={() => {
                  setResumeMode('ASK_EVERY_TIME');
                  startVideoPlayback(videos[0], videos);
                }}
                className="px-3 py-2.5 rounded-xl bg-[#121216] hover:bg-[#1a1a22] border border-white/10 text-xs font-semibold flex items-center gap-2"
              >
                <Play className="w-3.5 h-3.5" style={{ color: palette.accent }} />
                <span className="truncate">اختبار السؤال عند التشغيل</span>
              </button>
              <button
                onClick={() => {
                  setPlayerFullscreen(false);
                  setIsLandscape(false);
                  setShowVault(true);
                }}
                className="px-3 py-2.5 rounded-xl bg-[#121216] hover:bg-[#1a1a22] border border-white/10 text-xs font-semibold flex items-center gap-2"
              >
                <Lock className="w-3.5 h-3.5" style={{ color: palette.accent }} />
                <span className="truncate">فتح الخزنة الخاصة</span>
              </button>
            </div>
          </div>

          {/* Live Synced Runtime State Readout */}
          <div className="pt-4 border-t border-white/10 space-y-2.5">
            <h2 className="text-sm font-bold text-[#faf5eb]">الحالة الفعلية للمشغل الآن</h2>
            <div className="grid grid-cols-2 gap-x-6 gap-y-2 text-xs">
              <div className="flex justify-between gap-2 py-1 border-b border-white/5">
                <span className="text-[#c3b8a2]">وضع التشغيل</span>
                <span className="font-semibold truncate">{activePlaybackObj.title}</span>
              </div>
              <div className="flex justify-between gap-2 py-1 border-b border-white/5">
                <span className="text-[#c3b8a2]">وضع الاستئناف</span>
                <span className="font-semibold truncate">{activeResumeObj.title}</span>
              </div>
              <div className="flex justify-between gap-2 py-1 border-b border-white/5">
                <span className="text-[#c3b8a2]">السرعة المحفوظة / الحالية</span>
                <span className="font-mono-tabular font-semibold">
                  {savedSpeed}x / {isTempSpeedActive ? '2.0x' : `${savedSpeed}x`}
                </span>
              </div>
              <div className="flex justify-between gap-2 py-1 border-b border-white/5">
                <span className="text-[#c3b8a2]">الموضع والتكبير</span>
                <span className="font-mono-tabular font-semibold">
                  {formatTime(currentSec)} · {zoomScale.toFixed(1)}x
                </span>
              </div>
            </div>
            <p className="text-xs text-[#c3b8a2] leading-relaxed">
              تلميح داخل المشغل: اضغط مطولًا على الشاشة للتسريع المؤقت 2.0x، أو انقر مرتين يمينًا/يسارًا للتقديم والتأخير 10 ثوانٍ، أو اسحب على الحواف لتغيير الصوت والسطوع.
            </p>
          </div>

          {/* Captured Video Screenshots Gallery (updates when user taps Camera button inside player) */}
          {capturedFrames.length > 0 && (
            <div className="pt-4 border-t border-white/10 space-y-2.5">
              <h2 className="text-sm font-bold text-[#faf5eb] flex items-center gap-2">
                <Camera className="w-4 h-4" style={{ color: palette.accent }} />
                <span>لقطات الشاشة الملتقطة (Pictures/لقطات فيديوهاتك)</span>
              </h2>
              <div className="grid grid-cols-3 gap-2">
                {capturedFrames.slice(0, 6).map((frame) => (
                  <a
                    key={frame.id}
                    href={frame.dataUrl}
                    download={frame.filename}
                    className="group block rounded-xl overflow-hidden border border-white/10 bg-[#121216]"
                    title="تنزيل اللقطة"
                  >
                    <img
                      src={frame.dataUrl}
                      alt={frame.filename}
                      referrerPolicy="no-referrer"
                      className="w-full h-16 object-cover"
                    />
                    <div className="px-2 py-1 text-[10px] font-mono-tabular text-[#c3b8a2] flex justify-between">
                      <span>{frame.timestamp}</span>
                      <span>PNG</span>
                    </div>
                  </a>
                ))}
              </div>
            </div>
          )}
        </aside>
      </main>
    </div>
  );
}
