const FOCUSABLE_SELECTOR =
  'button:not([disabled]), [href], input:not([disabled]), [tabindex]:not([tabindex="-1"])';

/** Lists the elements inside a dialog that can take keyboard focus, in DOM order. */
export function focusableElements(container: HTMLElement): HTMLElement[] {
  return Array.from(container.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR))
    .filter((element) => element.getAttribute("aria-hidden") !== "true");
}

/**
 * Keeps Tab and Shift+Tab inside a modal dialog by wrapping at both ends.
 * Call it from a keydown handler; other keys are ignored.
 */
export function trapTabKey(event: KeyboardEvent, dialog: HTMLElement): void {
  if (event.key !== "Tab") return;
  const elements = focusableElements(dialog);
  if (!elements.length) {
    event.preventDefault();
    dialog.focus();
    return;
  }
  const current = elements.indexOf(document.activeElement as HTMLElement);
  if (event.shiftKey && current <= 0) {
    event.preventDefault();
    elements[elements.length - 1]?.focus();
  } else if (!event.shiftKey && (current === -1 || current === elements.length - 1)) {
    event.preventDefault();
    elements[0]?.focus();
  }
}
