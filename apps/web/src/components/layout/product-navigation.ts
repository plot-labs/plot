export const productNavigationItems = [
  { href: "/home", label: "Overview" },
  { href: "/activity", label: "Activity" },
  { href: "/updates", label: "Updates" },
] as const;

export const settingsNavigationItems = [
  { href: "/settings/general", label: "Workspace" },
  { href: "/settings/content", label: "Content" },
  { href: "/settings/integrations", label: "Connections" },
  { href: "/settings/autonomy", label: "Autonomy" },
  { href: "/settings/account", label: "Account" },
] as const;

export function isSettingsPath(pathname: string) {
  return pathname === "/settings" || pathname.startsWith("/settings/") || pathname === "/routines";
}

export function navigationPath(pathname: string) {
  if (pathname === "/artifacts") return "/updates";
  if (pathname === "/routines") return "/settings/autonomy";
  return pathname;
}
