import type { ReactNode } from "react";

// Public reader pages follow the reader's operating-system color scheme (see the dark variant in globals.css).
export default function PublicLayout({ children }: { children: ReactNode }) {
  return <div className="reader-theme">{children}</div>;
}
