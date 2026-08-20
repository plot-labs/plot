"use client";

import { Node as TiptapNode, mergeAttributes } from "@tiptap/core";
import { NodeViewWrapper, ReactNodeViewRenderer, type NodeViewProps } from "@tiptap/react";
import { Citation } from "@astryxdesign/core/Citation";
import { ExternalLink, ChevronLeft, ChevronRight, X } from "lucide-react";
import { useState, useId, useRef, useEffect, type ReactNode } from "react";

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
            icon: getSourceProviderIcon(primarySource),
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
              {getSourceProviderIcon(currentSource)}
              <span>{currentSource.provider || resolveProviderName(currentSource)}</span>
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

function getSourceProviderIcon(source?: CitationSourceItem): ReactNode {
  const url = (source?.url || "").toLowerCase();
  const provider = (source?.provider || "").toLowerCase();

  if (url.includes("github.com") || provider === "github") {
    return (
      <svg role="img" aria-label="GitHub" viewBox="0 0 24 24" className="size-3 fill-current">
        <path d="M12 .297c-6.63 0-12 5.373-12 12 0 5.303 3.438 9.8 8.205 11.385.6.113.82-.258.82-.577 0-.285-.01-1.04-.015-2.04-3.338.724-4.042-1.61-4.042-1.61C4.422 18.07 3.633 17.7 3.633 17.7c-1.087-.744.084-.729.084-.729 1.205.084 1.838 1.236 1.838 1.236 1.07 1.835 2.809 1.305 3.495.998.108-.776.417-1.305.76-1.605-2.665-.3-5.466-1.332-5.466-5.93 0-1.31.465-2.38 1.235-3.22-.135-.303-.54-1.523.105-3.176 0 0 1.005-.322 3.3 1.23.96-.267 1.98-.399 3-.405 1.02.006 2.04.138 3 .405 2.28-1.552 3.285-1.23 3.285-1.23.645 1.653.24 2.873.12 3.176.765.84 1.23 1.91 1.23 3.22 0 4.61-2.805 5.625-5.475 5.92.42.36.81 1.096.81 2.22 0 1.606-.015 2.896-.015 3.286 0 .315.21.69.825.57C20.565 22.092 24 17.592 24 12.297c0-6.627-5.373-12-12-12" />
      </svg>
    );
  }

  if (url.includes("linear.app") || provider === "linear") {
    return (
      <svg role="img" aria-label="Linear" viewBox="0 0 24 24" className="size-3 fill-[#5e6ad2]">
        <path d="M2.886 4.18A11.982 11.982 0 0 1 11.99 0C18.624 0 24 5.376 24 12.009c0 3.64-1.62 6.903-4.18 9.105L2.887 4.18ZM1.817 5.626l16.556 16.556c-.524.33-1.075.62-1.65.866L.951 7.277c.247-.575.537-1.126.866-1.65ZM.322 9.163l14.515 14.515c-.71.172-1.443.282-2.195.322L0 11.358a12 12 0 0 1 .322-2.195Zm-.17 4.862 9.823 9.824a12.02 12.02 0 0 1-9.824-9.824Z" />
      </svg>
    );
  }

  if (url.includes("notion.so") || provider === "notion") {
    return (
      <svg role="img" aria-label="Notion" viewBox="0 0 24 24" className="size-3 fill-current">
        <path d="M4.459 4.208c.746.606 1.026.56 2.428.466l13.215-.793c.28 0 .047-.28-.046-.326L17.86 1.968c-.42-.326-.981-.7-2.055-.607L3.01 2.295c-.466.046-.56.28-.374.466zm.793 3.08v13.904c0 .747.373 1.027 1.214.98l14.523-.84c.841-.046.935-.56.935-1.167V6.354c0-.606-.233-.933-.748-.887l-15.177.887c-.56.047-.747.327-.747.933zm14.337.745c.093.42 0 .84-.42.888l-.7.14v10.264c-.608.327-1.168.514-1.635.514-.748 0-.935-.234-1.495-.933l-4.577-7.186v6.952L12.21 19s0 .84-1.168.84l-3.222.186c-.093-.186 0-.653.327-.746l.84-.233V9.854L7.822 9.76c-.094-.42.14-1.026.793-1.073l3.456-.233 4.764 7.279v-6.44l-1.215-.139c-.093-.514.28-.887.747-.933zM1.936 1.035l13.31-.98c1.634-.14 2.055-.047 3.082.7l4.249 2.986c.7.513.934.653.934 1.213v16.378c0 1.026-.373 1.634-1.68 1.726l-15.458.934c-.98.047-1.448-.093-1.962-.747l-3.129-4.06c-.56-.747-.793-1.306-.793-1.96V2.667c0-.839.374-1.54 1.447-1.632z" />
      </svg>
    );
  }

  if (url.includes("slack.com") || provider === "slack") {
    return (
      <svg role="img" aria-label="Slack" viewBox="0 0 24 24" className="size-3">
        <path fill="#e01e5a" d="M5.04 15.17a2.52 2.52 0 1 1-2.52-2.52h2.52v2.52Zm1.27 0a2.52 2.52 0 0 1 5.04 0v6.31a2.52 2.52 0 1 1-5.04 0v-6.31Z" />
        <path fill="#36c5f0" d="M8.83 5.04a2.52 2.52 0 1 1 2.52-2.52v2.52H8.83Zm0 1.27a2.52 2.52 0 0 1 0 5.04H2.52a2.52 2.52 0 1 1 0-5.04h6.31Z" />
        <path fill="#2eb67d" d="M18.96 8.83a2.52 2.52 0 1 1 2.52 2.52h-2.52V8.83Zm-1.27 0a2.52 2.52 0 0 1-5.04 0V2.52a2.52 2.52 0 1 1 5.04 0v6.31Z" />
        <path fill="#ecb22e" d="M15.17 18.96a2.52 2.52 0 1 1-2.52 2.52v-2.52h2.52Zm0-1.27a2.52 2.52 0 0 1 0-5.04h6.31a2.52 2.52 0 1 1 0 5.04h-6.31Z" />
      </svg>
    );
  }

  if (url.includes("figma.com") || provider === "figma") {
    return (
      <svg role="img" aria-label="Figma" viewBox="0 0 38 57" className="size-3">
        <path fill="#f24e1e" d="M0 9.5A9.5 9.5 0 0 1 9.5 0H19v19H9.5A9.5 9.5 0 0 1 0 9.5Z" />
        <path fill="#ff7262" d="M19 0h9.5a9.5 9.5 0 1 1 0 19H19V0Z" />
        <path fill="#a259ff" d="M0 28.5A9.5 9.5 0 0 1 9.5 19H19v19H9.5A9.5 9.5 0 0 1 0 28.5Z" />
        <path fill="#1abcfe" d="M19 19h9.5a9.5 9.5 0 1 1 0 19H19V19Z" />
        <path fill="#0acf83" d="M0 47.5A9.5 9.5 0 0 1 9.5 38H19v9.5a9.5 9.5 0 0 1-19 0Z" />
      </svg>
    );
  }

  return (
    <svg role="img" aria-label="Source" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="size-3">
      <circle cx="12" cy="12" r="10" />
      <line x1="2" y1="12" x2="22" y2="12" />
      <path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z" />
    </svg>
  );
}

function resolveProviderName(source?: CitationSourceItem): string {
  const url = (source?.url || "").toLowerCase();
  if (url.includes("github.com")) return "GitHub";
  if (url.includes("linear.app")) return "Linear";
  if (url.includes("notion.so")) return "Notion";
  if (url.includes("slack.com")) return "Slack";
  if (url.includes("figma.com")) return "Figma";
  return "Source";
}
