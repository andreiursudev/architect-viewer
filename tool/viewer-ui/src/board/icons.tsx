import type { ComponentType } from '../api/types';

const common = {
  width: 18,
  height: 18,
  viewBox: '0 0 24 24',
  fill: 'none',
  stroke: 'currentColor',
  strokeWidth: 1.8,
  strokeLinecap: 'round' as const,
  strokeLinejoin: 'round' as const,
  'aria-hidden': true,
};

export function TypeIcon({ type }: { type: ComponentType }) {
  switch (type) {
    case 'database':
      return (
        <svg {...common}>
          <ellipse cx="12" cy="5.5" rx="7.5" ry="3" />
          <path d="M4.5 5.5v13c0 1.7 3.4 3 7.5 3s7.5-1.3 7.5-3v-13" />
          <path d="M4.5 12c0 1.7 3.4 3 7.5 3s7.5-1.3 7.5-3" />
        </svg>
      );
    case 'client':
      return (
        <svg {...common}>
          <rect x="3" y="4" width="18" height="12" rx="2" />
          <path d="M8 20h8M12 16v4" />
        </svg>
      );
    default:
      return (
        <svg {...common}>
          <rect x="3.5" y="4" width="17" height="6.5" rx="1.8" />
          <rect x="3.5" y="13.5" width="17" height="6.5" rx="1.8" />
          <path d="M7 7.2h.01M7 16.8h.01" />
        </svg>
      );
  }
}

export function NoteIcon() {
  return (
    <svg {...common} width={14} height={14}>
      <path d="M6 3h9l4 4v14H6z" />
      <path d="M14 3v5h5M9 13h7M9 17h5" />
    </svg>
  );
}
