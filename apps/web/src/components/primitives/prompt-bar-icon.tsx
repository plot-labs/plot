/** Stroke icon frame shared by the prompt bar and its menus. */
export function Icon({ children, size = 15, strokeWidth = 1.8 }: { children: React.ReactNode; size?: number; strokeWidth?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={strokeWidth} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      {children}
    </svg>
  );
}

const GLIDE_TRANSITION =
  "top 220ms cubic-bezier(0.23,1,0.32,1), height 220ms cubic-bezier(0.23,1,0.32,1), opacity 150ms ease";

/**
 * A single highlight that glides to the active row of a menu instead of each
 * row toggling its own background.
 */
export function GlidingHighlight({
  box,
  visible,
  className,
}: {
  box: { top: number; height: number } | null;
  visible: boolean;
  className: string;
}) {
  return (
    <span
      aria-hidden
      className={`pointer-events-none absolute bg-hover ${className}`}
      style={{
        top: box?.top ?? 0,
        height: box?.height ?? 0,
        opacity: box && visible ? 1 : 0,
        transition: GLIDE_TRANSITION,
      }}
    />
  );
}
