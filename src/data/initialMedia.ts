import sunsetMountainsImg from '../assets/images/thumb_sunset_mountains_1794680213777.jpg';
import oceanDeepImg from '../assets/images/thumb_ocean_deep_1794680213891.jpg';
import forestWaterfallImg from '../assets/images/thumb_forest_waterfall_1794680215348.jpg';
import galaxyCosmosImg from '../assets/images/thumb_galaxy_cosmos_1794680215925.jpg';

export type SortOrder = 'DATE_DESC' | 'DATE_ASC' | 'NAME_ASC' | 'NAME_DESC' | 'SIZE_DESC' | 'DURATION_DESC';

export type PlaybackMode =
  | 'STOP_AFTER_CURRENT'
  | 'SHUFFLE_ALL'
  | 'PLAY_ALL_IN_ORDER'
  | 'REPEAT_ONE'
  | 'REPEAT_PLAYLIST';

export type ResumeMode = 'RESUME' | 'START_FROM_BEGINNING' | 'ASK_EVERY_TIME';

export const PLAYBACK_MODES: Array<{ id: PlaybackMode; title: string; description: string }> = [
  { id: 'STOP_AFTER_CURRENT', title: 'إيقاف بعد الفيديو الحالي', description: 'يتوقف التشغيل تلقائيًا عند انتهاء المقطع الحالي' },
  { id: 'SHUFFLE_ALL', title: 'خلط جميع الفيديوهات', description: 'تشغيل المقاطع بترتيب عشوائي متواصل' },
  { id: 'PLAY_ALL_IN_ORDER', title: 'تشغيل الكل بالترتيب', description: 'الانتقال التلقائي للفيديو التالي بالترتيب' },
  { id: 'REPEAT_ONE', title: 'تكرار الفيديو الحالي', description: 'إعادة تشغيل المقطع الحالي تلقائيًا بعد انتهائه' },
  { id: 'REPEAT_PLAYLIST', title: 'تكرار القائمة كاملة', description: 'تكرار تشغيل جميع الفيديوهات أو قائمة التشغيل بشكل دائري' },
];

export const RESUME_MODES: Array<{ id: ResumeMode; title: string; description: string }> = [
  { id: 'RESUME', title: 'استئناف من آخر موضع', description: 'يكمل الفيديو تلقائيًا من الثانية التي توقفت عندها' },
  { id: 'START_FROM_BEGINNING', title: 'البدء من البداية دائمًا', description: 'يبدأ أي فيديو من الثانية 00:00 في كل مرة' },
  { id: 'ASK_EVERY_TIME', title: 'السؤال عند كل فيديو', description: 'يسألك إن كنت تريد الاستئناف أو البدء من البداية إذا سبق تشغيله' },
];

export interface VirtualVideo {
  id: number;
  title: string;
  folderName: string;
  durationSec: number;
  sizeMb: number;
  dateAddedSec: number;
  resolution: '4K' | '1080p' | '720p';
  width: number;
  height: number;
  thumbnail: string;
  accentTone: string;
  isVault?: boolean;
  subtitleAr: string;
  subtitleEn: string;
}

export interface VirtualPlaylist {
  id: number;
  name: string;
  videoIds: number[];
}

export const INITIAL_VIDEOS: VirtualVideo[] = [
  {
    id: 101,
    title: 'قمم الجبال عند الغروب — وثائقي',
    folderName: 'الأفلام والوثائقيات',
    durationSec: 225,
    sizeMb: 486.4,
    dateAddedSec: 1731500000,
    resolution: '4K',
    width: 3840,
    height: 2160,
    thumbnail: sunsetMountainsImg,
    accentTone: '#E5B84B',
    subtitleAr: 'مشهد بانورامي للجبال الشاهقة وانعكاس الشمس الذهبية',
    subtitleEn: 'Panoramic view of alpine peaks at golden hour',
  },
  {
    id: 102,
    title: 'أعماق المحيط الأزرق',
    folderName: 'كاميرا الهاتف (DCIM)',
    durationSec: 312,
    sizeMb: 294.8,
    dateAddedSec: 1731400000,
    resolution: '1080p',
    width: 1920,
    height: 1080,
    thumbnail: oceanDeepImg,
    accentTone: '#4EA8FF',
    subtitleAr: 'أشعة الشمس تخترق المياه الزرقاء الصافية للشعاب المرجانية',
    subtitleEn: 'Sunlight rays piercing deep sapphire ocean water',
  },
  {
    id: 103,
    title: 'شلالات الغابة المطيرة الاستوائية',
    folderName: 'مقاطع السفر',
    durationSec: 184,
    sizeMb: 178.2,
    dateAddedSec: 1731300000,
    resolution: '1080p',
    width: 1920,
    height: 1080,
    thumbnail: forestWaterfallImg,
    accentTone: '#2BE38A',
    subtitleAr: 'تدفق الشلال وسط الضباب الصباحي وأشجار الغابة العتيقة',
    subtitleEn: 'Morning sunbeams over lush rainforest waterfall mist',
  },
  {
    id: 104,
    title: 'سديم المجرة العميق — فلك 4K',
    folderName: 'التنزيلات',
    durationSec: 268,
    sizeMb: 612.0,
    dateAddedSec: 1731200000,
    resolution: '4K',
    width: 3840,
    height: 2160,
    thumbnail: galaxyCosmosImg,
    accentTone: '#B56BFF',
    subtitleAr: 'رحلة بصرية بين السدم الكونية وتكوين النجوم البعيدة',
    subtitleEn: 'Visual journey through deep space nebulae and starlight',
  },
];

export const INITIAL_VAULT_VIDEOS: VirtualVideo[] = [
  {
    id: 9001,
    title: 'تسجيل_خاص_مشفر_AES256.mp4',
    folderName: 'الخزنة الخاصة',
    durationSec: 195,
    sizeMb: 215.6,
    dateAddedSec: 1731550000,
    resolution: '1080p',
    width: 1920,
    height: 1080,
    thumbnail: sunsetMountainsImg,
    accentTone: '#FF3E54',
    isVault: true,
    subtitleAr: 'مقطع محمي بتشفير AES-256-GCM داخل الخزنة الخاصة',
    subtitleEn: 'Encrypted video inside VideoHatak Secure Vault',
  },
];

export const INITIAL_PLAYLISTS: VirtualPlaylist[] = [
  { id: 1, name: 'روائع الطبيعة 4K', videoIds: [101, 103, 102] },
  { id: 2, name: 'مشاهدة المساء', videoIds: [104, 101] },
];

export function formatTime(sec: number): string {
  const safe = Math.max(0, Math.floor(sec));
  const h = Math.floor(safe / 3600);
  const m = Math.floor((safe % 3600) / 60);
  const s = safe % 60;
  if (h > 0) {
    return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
  }
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
}
