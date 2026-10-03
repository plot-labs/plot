"use client";

import { useEffect, type RefObject } from "react";

const MENU_ITEM_SELECTOR = '[role="menuitem"]:not([disabled]), [role="menuitemcheckbox"]:not([disabled]), [role="menuitemradio"]:not([disabled])';

/**
 * Keyboard support for a role="menu" popup: focuses the first item when it opens and moves focus
 * with ArrowDown/ArrowUp (wrapping), Home and End. Escape and outside clicks stay with the caller.
 */
export function useMenuKeyboard(menuRef: RefObject<HTMLElement | null>, open: boolean) {
  useEffect(() => {
    const menu = menuRef.current;
    if (!open || !menu) return;

    const items = () => Array.from(menu.querySelectorAll<HTMLElement>(MENU_ITEM_SELECTOR));
    items()[0]?.focus();

    function onKeyDown(event: KeyboardEvent) {
      const list = items();
      if (!list.length) return;
      const current = list.indexOf(document.activeElement as HTMLElement);
      let next: number | null = null;
      if (event.key === "ArrowDown") next = current < 0 || current === list.length - 1 ? 0 : current + 1;
      else if (event.key === "ArrowUp") next = current <= 0 ? list.length - 1 : current - 1;
      else if (event.key === "Home") next = 0;
      else if (event.key === "End") next = list.length - 1;
      if (next === null) return;
      event.preventDefault();
      list[next]?.focus();
    }

    menu.addEventListener("keydown", onKeyDown);
    return () => menu.removeEventListener("keydown", onKeyDown);
  }, [menuRef, open]);
}
