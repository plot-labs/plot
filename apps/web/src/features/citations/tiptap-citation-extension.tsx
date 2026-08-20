"use client";

import { Node as TiptapNode, mergeAttributes } from "@tiptap/core";
import { NodeViewWrapper, ReactNodeViewRenderer, type NodeViewProps } from "@tiptap/react";
import { Citation } from "@astryxdesign/core/Citation";
import { ExternalLink, ChevronLeft, ChevronRight, X } from "lucide-react";
import { useState, useId, useRef, useEffect } from "react";

export type CitationSourceItem = {
  title: string;
  url: string;
  provider?: string;
  excerpt?: string;
};

export const TiptapCitationExtension = TiptapNode.create({
  name: "citation",
  group: "inline",
  inline: true,
  atom: true,
  selectable: true,

  addAttributes() {
    return {
      statementId: {
        default: null,
        parseHTML: (element) => element.getAttribute("data-statement-id"),
        renderHTML: (attributes) => ({
          "data-statement-id": attributes.statementId,
        }),
      },
      sources: {
        default: [],
        parseHTML: (element) => {
          try {
            const raw = element.getAttribute("data-sources");
            return raw ? JSON.parse(raw) : [];
          } catch {
            return [];
          }
        },
        renderHTML: (attributes) => ({
          "data-sources": JSON.stringify(attributes.sources || []),
        }),
      },
      number: {
        default: 1,
        parseHTML: (element) => Number(element.getAttribute("data-number")) || 1,
        renderHTML: (attributes) => ({
          "data-number": attributes.number,
        }),
      },
    };
  },

  parseHTML() {
    return [{ tag: "span[data-citation-node]" }];
  },

  renderHTML({ HTMLAttributes }) {
    return ["span", mergeAttributes(HTMLAttributes, { "data-citation-node": "" })];
  },

  addNodeView() {
    return ReactNodeViewRenderer(TiptapCitationNodeView);
  },
});

export function TiptapCitationNodeView({ node }: NodeViewProps) {
  const { sources = [], number = 1 } = node.attrs as {
    sources: CitationSourceItem[];
    number: number;
    statementId: string | null;
  };
  const [open, setOpen] = useState(false);
  const [currentIndex, setCurrentIndex] = useState(0);
  const popoverId = useId();
  const popoverRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLSpanElement>(null);

  const sourceList: CitationSourceItem[] = sources.length
    ? sources
    : [{ title: "Source", url: "#" }];
  const currentSource = sourceList[currentIndex] || sourceList[0];
  const primarySource = sourceList[0];
  const additionalCount = sourceList.length - 1;

  useEffect(() => {
    if (!open) return;

    function handleOutsideClick(event: Event) {
      if (
        event.target instanceof Node &&
        !popoverRef.current?.contains(event.target) &&
        !triggerRef.current?.contains(event.target)
      ) {
        setOpen(false);
      }
    }

    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        event.preventDefault();
        setOpen(false);
      }
    }

    document.addEventListener("pointerdown", handleOutsideClick, true);
    document.addEventListener("click", handleOutsideClick, true);
    document.addEventListener("keydown", handleKeyDown);
    return () => {
      document.removeEventListener("pointerdown", handleOutsideClick, true);
      document.removeEventListener("click", handleOutsideClick, true);
      document.removeEventListener("keydown", handleKeyDown);
    };
  }, [open]);

  return (
    <NodeViewWrapper as="span" className="relative inline-flex items-center align-baseline mx-0.5 select-none">
      <span
        ref={triggerRef}
        role="button"
        tabIndex={0}
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-controls={open ? popoverId : undefined}
        onClick={(e) => {
          e.preventDefault();
          e.stopPropagation();
          setOpen((prev) => !prev);
        }}
        onKeyDown={(e) => {
          if (e.key === "Enter" || e.key === " ") {
            e.preventDefault();
            setOpen((prev) => !prev);
          }
        }}
        className="inline-flex cursor-pointer items-center transition-transform hover:opacity-90 active:scale-95"
      >
        <Citation
          source={{
            title: additionalCount > 0 ? `${primarySource.title} +${additionalCount}` : primarySource.title,
            url: primarySource.url,
          }}
          number={number}
          variant="label"
        />
      </span>

      {open ? (
        <div
          ref={popoverRef}
          id={popoverId}
          role="dialog"
          aria-label="Citation details"
          className="absolute left-0 top-[calc(100%+8px)] z-50 w-[320px] rounded-2xl border border-black/10 bg-white/95 p-4 shadow-[0_16px_40px_rgba(0,0,0,0.12)] backdrop-blur-xl dark:border-white/10 dark:bg-[#1c1c1f]/95 dark:shadow-[0_16px_40px_rgba(0,0,0,0.4)] sm:w-[350px]"
        >
          {/* Header with Provider Badge and Navigation */}
          <div className="flex items-center justify-between pb-3 border-b border-black/[0.06] dark:border-white/[0.08]">
            <div className="inline-flex items-center gap-1.5 rounded-full border border-black/[0.08] bg-black/[0.03] px-2.5 py-0.5 text-[11px] font-medium text-black/70 dark:border-white/[0.08] dark:bg-white/[0.04] dark:text-white/75">
              <svg viewBox="0 0 16 16" fill="currentColor" className="size-3 shrink-0" aria-hidden="true">
                <path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.6-.18-3.28-.8-3.28-3.56 0-.88.31-1.6.82-2.17-.08-.2-.36-1.03.08-2.14 0 0 .67-.21 2.2.82A7.65 7.65 0 0 1 8 4.69c.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.11.16 1.94.08 2.14.51.57.82 1.29.82 2.17 0 2.77-1.69 3.38-3.29 3.56.29.25.54.74.54 1.5 0 1.08-.01 1.95-.01 2.22 0 .21.15.46.55.38A8.01 8.01 0 0 0 16 8c0-4.42-3.58-8-8-8Z" />
              </svg>
              <span>{currentSource.provider || "GitHub"}</span>
            </div>

            <div className="flex items-center gap-1.5">
              {sourceList.length > 1 ? (
                <div className="flex items-center gap-1">
                  <button
                    type="button"
                    disabled={currentIndex <= 0}
                    aria-label="Previous source"
                    onClick={(e) => {
                      e.stopPropagation();
                      setCurrentIndex((prev) => Math.max(0, prev - 1));
                    }}
                    className="inline-flex size-6 items-center justify-center rounded-md text-black/50 hover:bg-black/5 disabled:opacity-25 dark:text-white/50 dark:hover:bg-white/10"
                  >
                    <ChevronLeft className="size-3.5" />
                  </button>
                  <span className="text-[11px] font-mono font-medium text-black/40 dark:text-white/40">
                    {currentIndex + 1} / {sourceList.length}
                  </span>
                  <button
                    type="button"
                    disabled={currentIndex >= sourceList.length - 1}
                    aria-label="Next source"
                    onClick={(e) => {
                      e.stopPropagation();
                      setCurrentIndex((prev) => Math.min(sourceList.length - 1, prev + 1));
                    }}
                    className="inline-flex size-6 items-center justify-center rounded-md text-black/50 hover:bg-black/5 disabled:opacity-25 dark:text-white/50 dark:hover:bg-white/10"
                  >
                    <ChevronRight className="size-3.5" />
                  </button>
                </div>
              ) : null}

              <button
                type="button"
                aria-label="Close popover"
                onClick={(e) => {
                  e.stopPropagation();
                  setOpen(false);
                }}
                className="inline-flex size-6 items-center justify-center rounded-md text-black/45 hover:bg-black/5 hover:text-black dark:text-white/45 dark:hover:bg-white/10 dark:hover:text-white"
              >
                <X className="size-3.5" />
              </button>
            </div>
          </div>

          {/* Body */}
          <div className="pt-3">
            <h3 className="line-clamp-2 text-[13px] font-medium leading-snug tracking-[-0.01em] text-black/90 dark:text-white/92">
              {currentSource.title}
            </h3>

            {currentSource.excerpt ? (
              <div className="mt-2.5 rounded-xl border border-black/[0.06] bg-black/[0.02] p-2.5 text-xs leading-relaxed text-black/60 dark:border-white/[0.06] dark:bg-white/[0.03] dark:text-white/60">
                {currentSource.excerpt}
              </div>
            ) : null}

            {currentSource.url && currentSource.url !== "#" ? (
              <div className="mt-3 flex items-center justify-between border-t border-black/[0.06] pt-2.5 dark:border-white/[0.08]">
                <span className="truncate max-w-[220px] font-mono text-[11px] text-black/40 dark:text-white/40">
                  {currentSource.url.replace(/^https?:\/\/(www\.)?/, "")}
                </span>
                <a
                  href={currentSource.url}
                  target="_blank"
                  rel="noreferrer noopener"
                  onClick={(e) => e.stopPropagation()}
                  className="inline-flex items-center gap-1 text-xs font-medium text-black/70 hover:text-black hover:underline dark:text-white/70 dark:hover:text-white"
                >
                  <span>Open</span>
                  <ExternalLink className="size-3 shrink-0 opacity-70" />
                </a>
              </div>
            ) : null}
          </div>
        </div>
      ) : null}
    </NodeViewWrapper>
  );
}
