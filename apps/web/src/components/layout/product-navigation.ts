export const productNavigationItems = [
  { href: "/chat", label: "Work" },
  { href: "/routines", label: "Automations" },
  { href: "/artifacts", label: "Library" },
  { href: "/settings/integrations", label: "Connections" },
] as const;

export function isSettingsPath(pathname: string) {
  return (pathname === "/settings" || pathname.startsWith("/settings/"))
    && pathname !== "/settings/integrations"
    && !pathname.startsWith("/settings/integrations/");
}
