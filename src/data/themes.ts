export type AppThemeId =
  | 'OBSIDIAN_ELITE'
  | 'SAPPHIRE_PREMIUM'
  | 'EMERALD_LUXURY'
  | 'GALAXY_PRO'
  | 'ROYAL_IVORY'
  | 'TITANIUM_NOIR'
  | 'RUBY_CINEMA'
  | 'NEON_DREAM';

export type ThemeModeSetting = 'SYSTEM' | 'DARK' | 'LIGHT';

export interface VideoHatakThemePalette {
  id: AppThemeId;
  displayName: string;
  arabicTagline: string;
  isLightByDefault: boolean;
  isDark: boolean;
  background: string;
  surface: string;
  surfaceElevated: string;
  accent: string;
  accentGlow: string;
  secondaryAccent: string;
  onAccent: string;
  border: string;
  onSurface: string;
  onSurfaceMuted: string;
  topBarStart: string;
  topBarEnd: string;
  seekBarPlayedStart: string;
  seekBarPlayedEnd: string;
  seekBarGlow: string;
  seekBarUnplayed: string;
  seekBarThumb: string;
}

export const ALL_THEMES: Array<{
  id: AppThemeId;
  displayName: string;
  arabicTagline: string;
  isLightByDefault: boolean;
}> = [
  { id: 'OBSIDIAN_ELITE', displayName: 'Obsidian Elite', arabicTagline: 'الفخامة المطلقة', isLightByDefault: false },
  { id: 'SAPPHIRE_PREMIUM', displayName: 'Sapphire Premium', arabicTagline: 'الأناقة العصرية', isLightByDefault: false },
  { id: 'EMERALD_LUXURY', displayName: 'Emerald Luxury', arabicTagline: 'الفخامة الهادئة', isLightByDefault: false },
  { id: 'GALAXY_PRO', displayName: 'Galaxy Pro', arabicTagline: 'عالم من الإبداع', isLightByDefault: false },
  { id: 'ROYAL_IVORY', displayName: 'Royal Ivory', arabicTagline: 'أناقة خالدة', isLightByDefault: true },
  { id: 'TITANIUM_NOIR', displayName: 'Titanium Noir', arabicTagline: 'قوة التميز', isLightByDefault: false },
  { id: 'RUBY_CINEMA', displayName: 'Ruby Cinema', arabicTagline: 'شغف المشاهدة', isLightByDefault: false },
  { id: 'NEON_DREAM', displayName: 'Neon Dream', arabicTagline: 'مستقبل الترفيه', isLightByDefault: false },
];

export function resolvePalette(themeId: AppThemeId, themeMode: ThemeModeSetting): VideoHatakThemePalette {
  const meta = ALL_THEMES.find((t) => t.id === themeId) ?? ALL_THEMES[0];
  const forceLight = themeMode === 'LIGHT' || (themeMode === 'SYSTEM' && meta.isLightByDefault);

  if (themeId === 'ROYAL_IVORY' || forceLight) {
    const lightAccents: Record<AppThemeId, { accent: string; glow: string; secondary: string; border: string }> = {
      OBSIDIAN_ELITE: { accent: '#B4820A', glow: '#E5B84B', secondary: '#8C6308', border: '#D6C08D' },
      SAPPHIRE_PREMIUM: { accent: '#1D6FD8', glow: '#60A5FA', secondary: '#0284C7', border: '#BFD7F5' },
      EMERALD_LUXURY: { accent: '#059669', glow: '#34D399', secondary: '#0D9488', border: '#B5E3D0' },
      GALAXY_PRO: { accent: '#7E22CE', glow: '#C084FC', secondary: '#A855F7', border: '#DBC7F5' },
      ROYAL_IVORY: { accent: '#B4820A', glow: '#E5B84B', secondary: '#8C6308', border: '#D6C08D' },
      TITANIUM_NOIR: { accent: '#334155', glow: '#64748B', secondary: '#475569', border: '#CBD5E1' },
      RUBY_CINEMA: { accent: '#BE123C', glow: '#FB7185', secondary: '#E11D48', border: '#F3C1CC' },
      NEON_DREAM: { accent: '#0891B2', glow: '#22D3EE', secondary: '#C026D3', border: '#BAE6FD' },
    };
    const c = lightAccents[themeId];
    const isIvory = themeId === 'ROYAL_IVORY';
    return {
      id: themeId,
      displayName: meta.displayName,
      arabicTagline: meta.arabicTagline,
      isLightByDefault: meta.isLightByDefault,
      isDark: false,
      background: isIvory ? '#FAF5EA' : '#F6F7FB',
      surface: '#FFFFFF',
      surfaceElevated: isIvory ? '#F1E7D2' : '#EBEEF5',
      accent: c.accent,
      accentGlow: c.glow,
      secondaryAccent: c.secondary,
      onAccent: '#FFFFFF',
      border: c.border,
      onSurface: '#1A1610',
      onSurfaceMuted: '#5D5342',
      topBarStart: '#FFFFFF',
      topBarEnd: isIvory ? '#F1E7D2' : '#EBEEF5',
      seekBarPlayedStart: c.accent,
      seekBarPlayedEnd: c.glow,
      seekBarGlow: `${c.glow}73`,
      seekBarUnplayed: `${c.accent}40`,
      seekBarThumb: c.accent,
    };
  }

  const darkMap: Record<Exclude<AppThemeId, 'ROYAL_IVORY'>, Omit<VideoHatakThemePalette, 'id' | 'displayName' | 'arabicTagline' | 'isLightByDefault' | 'isDark'>> = {
    OBSIDIAN_ELITE: {
      background: '#070709',
      surface: '#121216',
      surfaceElevated: '#1C1A16',
      accent: '#E5B84B',
      accentGlow: '#FFD875',
      secondaryAccent: '#C59326',
      onAccent: '#141004',
      border: '#4A3B18',
      onSurface: '#FAF5EB',
      onSurfaceMuted: '#C3B8A2',
      topBarStart: '#0D0C0A',
      topBarEnd: '#18140C',
      seekBarPlayedStart: '#C59326',
      seekBarPlayedEnd: '#FFDF85',
      seekBarGlow: 'rgba(255, 210, 90, 0.55)',
      seekBarUnplayed: 'rgba(255, 231, 163, 0.24)',
      seekBarThumb: '#FFE599',
    },
    SAPPHIRE_PREMIUM: {
      background: '#040A16',
      surface: '#0B1628',
      surfaceElevated: '#12233F',
      accent: '#4EA8FF',
      accentGlow: '#93CCFF',
      secondaryAccent: '#BBD7F5',
      onAccent: '#031226',
      border: '#1E3C68',
      onSurface: '#F0F7FF',
      onSurfaceMuted: '#9BB4D4',
      topBarStart: '#060E1F',
      topBarEnd: '#0F203B',
      seekBarPlayedStart: '#2563EB',
      seekBarPlayedEnd: '#7CC4FF',
      seekBarGlow: 'rgba(96, 181, 255, 0.55)',
      seekBarUnplayed: 'rgba(191, 219, 254, 0.24)',
      seekBarThumb: '#DCEEFF',
    },
    EMERALD_LUXURY: {
      background: '#04100A',
      surface: '#0A1D13',
      surfaceElevated: '#122E20',
      accent: '#2BE38A',
      accentGlow: '#86F8C0',
      secondaryAccent: '#14B8A6',
      onAccent: '#031A0F',
      border: '#194B32',
      onSurface: '#ECFDF4',
      onSurfaceMuted: '#98C9B0',
      topBarStart: '#06160D',
      topBarEnd: '#0D281A',
      seekBarPlayedStart: '#059669',
      seekBarPlayedEnd: '#5BF2AB',
      seekBarGlow: 'rgba(52, 235, 149, 0.55)',
      seekBarUnplayed: 'rgba(167, 243, 208, 0.24)',
      seekBarThumb: '#D1FAE5',
    },
    GALAXY_PRO: {
      background: '#080413',
      surface: '#130B26',
      surfaceElevated: '#1E133A',
      accent: '#B56BFF',
      accentGlow: '#D8A8FF',
      secondaryAccent: '#E879F9',
      onAccent: '#130524',
      border: '#3D226B',
      onSurface: '#F6F0FF',
      onSurfaceMuted: '#B8A2DB',
      topBarStart: '#0B061A',
      topBarEnd: '#1B0F36',
      seekBarPlayedStart: '#9333EA',
      seekBarPlayedEnd: '#E09EFF',
      seekBarGlow: 'rgba(200, 117, 255, 0.55)',
      seekBarUnplayed: 'rgba(233, 213, 255, 0.24)',
      seekBarThumb: '#F3E8FF',
    },
    TITANIUM_NOIR: {
      background: '#060608',
      surface: '#121317',
      surfaceElevated: '#1E2026',
      accent: '#DCE3EC',
      accentGlow: '#FFFFFF',
      secondaryAccent: '#94A3B8',
      onAccent: '#0A0B0E',
      border: '#3B404A',
      onSurface: '#F8FAFC',
      onSurfaceMuted: '#A3ABB8',
      topBarStart: '#0A0B0E',
      topBarEnd: '#181A20',
      seekBarPlayedStart: '#94A3B8',
      seekBarPlayedEnd: '#F8FAFC',
      seekBarGlow: 'rgba(226, 232, 240, 0.5)',
      seekBarUnplayed: 'rgba(203, 213, 225, 0.24)',
      seekBarThumb: '#FFFFFF',
    },
    RUBY_CINEMA: {
      background: '#100407',
      surface: '#1D090E',
      surfaceElevated: '#2D0F17',
      accent: '#FF3E54',
      accentGlow: '#FF8A98',
      secondaryAccent: '#FB7185',
      onAccent: '#FFFFFF',
      border: '#5E1B28',
      onSurface: '#FFF1F3',
      onSurfaceMuted: '#D49EA8',
      topBarStart: '#150509',
      topBarEnd: '#270B12',
      seekBarPlayedStart: '#DC2626',
      seekBarPlayedEnd: '#FF6B7D',
      seekBarGlow: 'rgba(255, 77, 98, 0.55)',
      seekBarUnplayed: 'rgba(254, 205, 211, 0.24)',
      seekBarThumb: '#FFE4E6',
    },
    NEON_DREAM: {
      background: '#060613',
      surface: '#0E0E24',
      surfaceElevated: '#171738',
      accent: '#22E5FF',
      accentGlow: '#8BF3FF',
      secondaryAccent: '#E040FB',
      onAccent: '#04141C',
      border: '#2B2C66',
      onSurface: '#ECFEFF',
      onSurfaceMuted: '#9FA7D6',
      topBarStart: '#09091B',
      topBarEnd: '#151236',
      seekBarPlayedStart: '#E040FB',
      seekBarPlayedEnd: '#22E5FF',
      seekBarGlow: 'rgba(34, 229, 255, 0.55)',
      seekBarUnplayed: 'rgba(165, 243, 252, 0.24)',
      seekBarThumb: '#E0F7FA',
    },
  };

  const p = darkMap[themeId];
  return {
    id: themeId,
    displayName: meta.displayName,
    arabicTagline: meta.arabicTagline,
    isLightByDefault: meta.isLightByDefault,
    isDark: true,
    ...p,
  };
}
