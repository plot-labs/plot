import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach } from "vitest";

function createInMemoryLocalStorage(): Storage {
  const store = new Map<string, string>();

  return {
    get length() {
      return store.size;
    },
    clear() {
      store.clear();
    },
    getItem(key: string) {
      return store.has(key) ? store.get(key)! : null;
    },
    key(index: number) {
      return Array.from(store.keys())[index] ?? null;
    },
    removeItem(key: string) {
      store.delete(key);
    },
    setItem(key: string, value: string) {
      store.set(key, String(value));
    },
  };
}

function installLocalStoragePolyfill() {
  if (typeof window === "undefined") {
    return;
  }

  let working = false;
  if (window.localStorage && typeof window.localStorage.setItem === "function") {
    try {
      window.localStorage.setItem("__plot_vitest__", "1");
      window.localStorage.removeItem("__plot_vitest__");
      working = true;
    } catch {
      working = false;
    }
  }

  if (!working) {
    Object.defineProperty(window, "localStorage", {
      configurable: true,
      value: createInMemoryLocalStorage(),
    });
  }
}

installLocalStoragePolyfill();

if (typeof window !== "undefined" && !window.matchMedia) {
  Object.defineProperty(window, "matchMedia", {
    configurable: true,
    value: (query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: () => undefined,
      removeListener: () => undefined,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
      dispatchEvent: () => false,
    }),
  });
}

if (typeof globalThis.ResizeObserver === "undefined") {
  class ResizeObserverMock {
    observe() {}
    unobserve() {}
    disconnect() {}
  }

  Object.defineProperty(globalThis, "ResizeObserver", {
    configurable: true,
    value: ResizeObserverMock,
  });
}

afterEach(() => cleanup());
