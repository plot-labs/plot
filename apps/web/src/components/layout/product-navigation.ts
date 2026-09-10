export const productNavigationItems = [
  { href: "/home", label: "Home" },
  { href: "/chat", label: "Chat" },
  { href: "/automation", label: "Automation" },
  { href: "/contents", label: "Contents" },
  { href: "/settings/integrations", label: "Connections" },
] as const;

export const settingsNavigationItems = [
  { href: "/settings/general", label: "Workspace" },
  { href: "/settings/content", label: "Content profile" },
  { href: "/settings/account", label: "Account" },
] as const;

export function isSettingsPath(pathname: string) {
  return (pathname === "/settings" || pathname.startsWith("/settings/")) && !["/settings/integrations"].some((path) => pathname === path || pathname.startsWith(`${path}/`));
}

export function navigationPath(pathname: string) {
  if (pathname.startsWith("/automation/")) return "/automation";
  return pathname;
}
