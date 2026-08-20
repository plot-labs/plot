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
          className="absolute left-0 top-[calc(100%+6px)] z-50 w-[300px] rounded-xl border border-black/10 bg-white p-3.5 shadow-[0_12px_32px_rgba(0,0,0,0.12)] dark:border-white/10 dark:bg-[#202024] sm:w-[340px]"
        >
          {/* Header with navigation */}
          <div className="flex items-center justify-between pb-2 border-b border-black/[0.06] dark:border-white/[0.08]">
            <div className="flex items-center gap-1">
              <button
                type="button"
                disabled={currentIndex <= 0}
                aria-label="Previous source"
                onClick={(e) => {
                  e.stopPropagation();
                  setCurrentIndex((prev) => Math.max(0, prev - 1));
                }}
                className="inline-flex size-6 items-center justify-center rounded-md text-black/50 hover:bg-black/5 disabled:opacity-30 dark:text-white/50 dark:hover:bg-white/10"
              >
                <ChevronLeft className="size-3.5" />
              </button>
              <button
                type="button"
                disabled={currentIndex >= sourceList.length - 1}
                aria-label="Next source"
                onClick={(e) => {
                  e.stopPropagation();
                  setCurrentIndex((prev) => Math.min(sourceList.length - 1, prev + 1));
                }}
                className="inline-flex size-6 items-center justify-center rounded-md text-black/50 hover:bg-black/5 disabled:opacity-30 dark:text-white/50 dark:hover:bg-white/10"
              >
                <ChevronRight className="size-3.5" />
              </button>
            </div>
            <span className="text-[11px] font-medium text-black/40 dark:text-white/40">
              {currentIndex + 1} / {sourceList.length}
            </span>
            <button
              type="button"
              aria-label="Close popover"
              onClick={(e) => {
                e.stopPropagation();
                setOpen(false);
              }}
              className="inline-flex size-6 items-center justify-center rounded-md text-black/50 hover:bg-black/5 dark:text-white/50 dark:hover:bg-white/10"
            >
              <X className="size-3.5" />
            </button>
          </div>

          {/* Body */}
          <div className="pt-2.5">
            <div className="text-xs font-semibold text-black/80 dark:text-white/85">
              {currentSource.provider || "GitHub"}
            </div>
            <div className="mt-1 line-clamp-2 text-[13px] font-medium leading-snug text-black/90 dark:text-white/92">
              {currentSource.title}
            </div>
            {currentSource.excerpt ? (
              <p className="mt-1.5 line-clamp-2 text-xs leading-relaxed text-black/50 dark:text-white/55">
                {currentSource.excerpt}
              </p>
            ) : null}
            {currentSource.url && currentSource.url !== "#" ? (
              <a
                href={currentSource.url}
                target="_blank"
                rel="noreferrer noopener"
                className="mt-2.5 inline-flex items-center gap-1 truncate text-xs text-[#2563eb] hover:underline dark:text-[#60a5fa]"
              >
                <span className="truncate">{currentSource.url}</span>
                <ExternalLink className="size-3 shrink-0" />
              </a>
            ) : null}
          </div>
        </div>
      ) : null}
    </NodeViewWrapper>
  );
}
