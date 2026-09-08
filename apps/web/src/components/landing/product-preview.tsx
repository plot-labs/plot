"use client";

import {
  MessageMultiple01Icon,
  MoreVerticalIcon,
  Shapes01Icon,
  ZapIcon,
} from "@hugeicons/core-free-icons";
import { HugeiconsIcon } from "@hugeicons/react";
import {
  ChatComposer as AstryxChatComposer,
  ChatComposerDrawer,
  ChatComposerInput,
  ChatMessage,
  ChatMessageBubble,
  ChatMessageList,
  ChatSendButton,
} from "@astryxdesign/core/Chat";
import { Citation } from "@astryxdesign/core/Citation";
import {
  Check,
  ChevronDown,
  ExternalLink,
  Eye,
  PanelLeftClose,
  Save,
  X,
} from "lucide-react";
import Image from "next/image";
import { useId, useState, type CSSProperties } from "react";

const initialDraft = [
  "Your next project is easier to find. This release brings search and pinned favorites to your workspace.",
  "Search your project list by name to get to the right project without scrolling.",
  "Pin the projects you return to most. They stay at the top of your list, ready when you are.",
].join("\n\n");

const sources = [
  { label: "#142 · Search projects by name (example)", shortLabel: "acme/product / #142" },
  { label: "#148 · Pin favorite projects (example)", shortLabel: "acme/product / #148" },
];

export function ProductPreview() {
  const [panel, setPanel] = useState<"chat" | "artifact">("artifact");
  const [draft, setDraft] = useState(initialDraft);
  const [savedDraft, setSavedDraft] = useState(initialDraft);
  const [sourcesOpen, setSourcesOpen] = useState(false);
  const [message, setMessage] = useState("");

  const saveState = draft === savedDraft ? "Saved" : "Unsaved changes";

  return (
    <div id="product-preview" className="scroll-mt-24">
      <div className="mb-4 flex flex-wrap items-center justify-between gap-2 text-xs text-muted-foreground">
        <span>The Plot workspace</span>
        <span className="font-mono text-[10px] uppercase tracking-wider">
          Product preview · Example data
        </span>
      </div>

      <div className="overflow-hidden rounded-xl border border-black/15 bg-[#eef0f3] shadow-[0_24px_80px_-30px_rgb(0_0_0_/_0.22)]">
        <div className="flex h-[720px] text-[#18181b]">
          <LandingProductSidebar />

          <div className="flex min-w-0 flex-1 flex-col bg-[#fbfbf8]">
            <div
              className="flex shrink-0 gap-1 border-b border-black/[0.08] p-2 lg:hidden"
              aria-label="Preview panels"
            >
              {(["chat", "artifact"] as const).map((value) => (
                <button
                  key={value}
                  type="button"
                  aria-pressed={panel === value}
                  onClick={() => setPanel(value)}
                  className={`min-h-10 flex-1 rounded-lg text-sm capitalize ${
                    panel === value ? "bg-white font-medium shadow-sm" : "text-black/55"
                  }`}
                >
                  {value}
                </button>
              ))}
            </div>

            <div className="flex min-h-0 flex-1">
              <section
                aria-label="Example Plot chat"
                className={`${panel === "chat" ? "flex" : "hidden"} min-w-0 flex-1 flex-col lg:flex`}
              >
                <LandingChatHeader />
                <div className="min-h-0 flex-1 overflow-y-auto bg-[#fbfbf8]">
                  <div className="mx-auto w-full max-w-[760px] px-4 pb-12 pt-8 sm:px-6">
                    <ChatMessageList density="compact" gap={4} style={{ flex: "none" }}>
                      <ChatMessage sender="user">
                        <ChatMessageBubble className="max-w-[min(680px,92%)]">
                          <p>Write a changelog for v2.4. Focus on what changed for our customers.</p>
                        </ChatMessageBubble>
                      </ChatMessage>

                      <ChatMessage sender="assistant">
                        <ChatMessageBubble variant="ghost" className="w-full min-w-0 max-w-full">
                          <p className="text-sm leading-6 text-black/65">
                            The source review is complete. The artifact is ready below.
                          </p>
                          <div className="mt-4 flex items-center gap-2 border-b border-black/[0.07] pb-3 text-xs text-black/55">
                            <Check aria-hidden="true" className="size-3.5 text-emerald-700" />
                            Read connected sources
                          </div>
                          <div className="mt-3 flex flex-wrap items-center gap-2" aria-label="Connected sources">
                            <span className="mr-1 text-xs font-medium text-black/55">
                              Sources <span className="font-normal text-black/35">2</span>
                            </span>
                            {sources.map((source, index) => (
                              <Citation
                                key={source.label}
                                source={{ title: source.shortLabel }}
                                number={index + 1}
                                variant="label"
                              />
                            ))}
                          </div>
                          <button
                            type="button"
                            onClick={() => setPanel("artifact")}
                            aria-expanded={panel === "artifact"}
                            className="mt-4 flex w-full items-center justify-between gap-4 rounded-xl border border-black/[0.08] bg-white/70 px-4 py-3 text-left transition hover:bg-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-black/20"
                          >
                            <span className="min-w-0 truncate text-sm font-medium text-black/82">
                              Less searching. More finding.
                            </span>
                            <span className="inline-flex shrink-0 items-center gap-1.5 rounded-full bg-black/[0.035] px-3 py-1.5 text-xs font-medium text-black/50">
                              <Eye aria-hidden="true" className="size-3.5" />
                              Open artifact
                            </span>
                          </button>
                          <p className="mt-4 text-xs text-black/42">6:00 PM · Source agent</p>
                        </ChatMessageBubble>
                      </ChatMessage>

                      {message ? (
                        <ChatMessage sender="user">
                          <ChatMessageBubble><p>{message}</p></ChatMessageBubble>
                        </ChatMessage>
                      ) : null}
                    </ChatMessageList>

                    {message ? (
                      <p role="status" className="mt-4 text-sm text-black/55">
                        This example stays in the preview.
                      </p>
                    ) : null}
                  </div>
                </div>
                <LandingChatComposer onSubmit={setMessage} />
              </section>

              <section
                id="landing-artifact-panel"
                aria-label="Example changelog document"
                className={`${panel === "artifact" ? "flex" : "hidden"} min-w-0 flex-1 flex-col lg:flex lg:border-l lg:border-black/[0.08]`}
              >
                <header className="relative flex min-h-16 shrink-0 flex-wrap items-center justify-between gap-2 bg-[#fbfbf8]/85 px-4 backdrop-blur-xl">
                  <LandingSourcesPopover open={sourcesOpen} onOpenChange={setSourcesOpen} />
                  <div className="flex items-center gap-2">
                    <span role="status" className="text-xs text-black/50">{saveState}</span>
                    <button
                      type="button"
                      onClick={() => setSavedDraft(draft)}
                      className="inline-flex min-h-8 items-center gap-1.5 rounded-lg bg-black px-3 text-xs font-medium text-white transition hover:bg-black/80 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-black/20"
                    >
                      <Save aria-hidden="true" className="size-3.5" />
                      Save draft
                    </button>
                  </div>
                </header>

                <div className="shrink-0 bg-[#fbfbf8] px-6 pb-3 pt-1">
                  <div className="text-[16px] font-medium leading-[22px] text-black/72">
                    Less searching. More finding.
                  </div>
                  <p className="mt-1 text-xs text-black/50">Example draft · Edits stay in this preview</p>
                </div>

                <div className="min-h-0 flex-1 overflow-y-auto bg-[#fbfbf8]">
                  <article className="min-h-full w-full max-w-[980px] px-[clamp(24px,5vw,48px)] pb-16 pt-[22px] text-black/88">
                    <div
                      aria-label="Document formatting"
                      className="mb-3 flex flex-wrap items-center gap-1.5 border-b border-black/[0.07] pb-2.5"
                    >
                      {["Heading", "Bulleted list", "Numbered list", "Move block up", "Move block down"].map((label) => (
                        <button
                          key={label}
                          type="button"
                          className="min-h-8 rounded-md border border-black/10 px-2.5 text-xs font-medium text-black/65 transition hover:bg-black/[0.04] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-400"
                        >
                          {label}
                        </button>
                      ))}
                    </div>
                    <div
                      role="textbox"
                      aria-label="Draft content"
                      contentEditable
                      suppressContentEditableWarning
                      onInput={(event) => setDraft(event.currentTarget.innerText)}
                      className="min-h-[540px] whitespace-pre-wrap text-[15px] leading-6 text-black/88 focus:outline-none [&_p]:mb-[22px]"
                    >
                      {draft}
                    </div>
                  </article>
                </div>
              </section>
            </div>
          </div>
        </div>
      </div>

      <p className="mt-4 text-center text-xs text-muted-foreground">
        Edit the draft and inspect its sources. Example content; nothing is published.
      </p>
    </div>
  );
}

function LandingProductSidebar() {
  const navigation = [
    { label: "Chat", icon: MessageMultiple01Icon },
    { label: "Routines", icon: ZapIcon },
    { label: "Artifacts", icon: Shapes01Icon },
  ];

  return (
    <aside
      aria-label="Example workspace sidebar"
      className="hidden h-full w-[252px] shrink-0 flex-col border-r border-black/[0.08] bg-[#f6f7f9] pr-px text-[#2f3237] lg:flex"
    >
      <div className="flex items-center gap-2 px-4 pb-4 pt-5">
        <div className="flex min-w-0 flex-1 items-center gap-2">
          <Image src="/plot-icon.svg" alt="" width={24} height={24} className="size-6 shrink-0" />
          <div className="font-display text-[22px] leading-none tracking-normal text-black/85">Plot</div>
        </div>
        <span className="inline-flex size-8 shrink-0 items-center justify-center rounded-[8px] text-black/35">
          <PanelLeftClose aria-hidden="true" className="size-4" />
        </span>
      </div>

      <div className="relative px-3 pb-4">
        <div className="mb-2 flex h-7 items-center pl-0.5">
          <div className="text-[11px] font-medium uppercase text-black/35">Workspace</div>
        </div>
        <div className="flex h-9 w-full items-center gap-2 rounded-[8px] border border-black/[0.12] bg-white/40 px-2 text-left text-[13px] font-semibold text-black/76">
          <span className="flex size-6 items-center justify-center rounded-[7px] bg-[#ec5b45] text-xs font-semibold text-white">A</span>
          <span className="min-w-0 flex-1 truncate">Acme</span>
          <ChevronDown aria-hidden="true" className="size-4 shrink-0 text-black/38" />
        </div>
      </div>

      <nav className="space-y-1 px-3 pb-4" aria-label="Product sidebar navigation">
        {navigation.map(({ label, icon }) => (
          <div key={label} className="flex h-8 items-center gap-2 rounded-[8px] px-2.5 text-[13px] font-medium text-black/65">
            <HugeiconsIcon icon={icon} size={16} color="currentColor" strokeWidth={1.5} aria-hidden="true" className="shrink-0" />
            <span>{label}</span>
          </div>
        ))}
      </nav>

      <div className="min-h-0 flex-1 overflow-y-auto px-3">
        <div className="px-2 pb-1 pt-2 text-[11px] font-medium uppercase tracking-[0.08em] text-black/35">History</div>
        <div className="flex h-8 w-full items-center gap-2 rounded-[8px] bg-white/75 px-2.5 text-left text-[13px] text-[#18181b] shadow-sm shadow-black/[0.03]">
          <HugeiconsIcon icon={MessageMultiple01Icon} size={14} color="currentColor" strokeWidth={1.5} aria-hidden="true" />
          <span className="min-w-0 flex-1 truncate">September product update</span>
        </div>
      </div>

      <div className="border-t border-black/[0.06] px-3 py-3">
        <div className="flex w-full items-center gap-2 rounded-[16px] px-1 py-1 text-left">
          <div className="flex size-8 items-center justify-center rounded-full bg-[#eadcff] text-xs font-semibold text-[#6f42a5]">A</div>
          <div className="min-w-0 flex-1">
            <div className="truncate text-[13px] font-semibold">Alex</div>
            <div className="truncate text-xs text-black/45">alex@example.com</div>
          </div>
          <HugeiconsIcon icon={MoreVerticalIcon} size={16} color="currentColor" strokeWidth={1.5} aria-hidden="true" className="ml-auto shrink-0 text-black/38" />
        </div>
      </div>
    </aside>
  );
}

function LandingChatHeader() {
  const fade: CSSProperties = {
    maskImage: "linear-gradient(black calc(100% - 12px), transparent 100%)",
    WebkitMaskImage: "linear-gradient(black calc(100% - 12px), transparent 100%)",
  };

  return (
    <header className="flex min-h-14 shrink-0 items-center bg-[#fbfbf8]/90 px-4 py-3 backdrop-blur-xl" style={fade}>
      <div className="flex w-full min-w-0 items-center justify-start gap-2 text-sm font-semibold text-black/78">
        <h3 className="truncate text-left">September product update</h3>
        <span aria-hidden="true" className="text-black/45">···</span>
      </div>
    </header>
  );
}

function LandingChatComposer({ onSubmit }: { onSubmit: (value: string) => void }) {
  const [value, setValue] = useState("");
  const labelId = useId();

  function submit() {
    const trimmed = value.trim();
    if (!trimmed) return;
    onSubmit(trimmed);
    setValue("");
  }

  return (
    <div className="w-full bg-[#fbfbf8]/95 px-4 pb-4 pt-3 backdrop-blur-xl sm:px-6">
      <AstryxChatComposer
        onSubmit={(submitted) => {
          onSubmit(submitted);
          setValue("");
        }}
        placeholder="Ask Plot to refine the draft…"
        density="balanced"
        elevation="none"
        drawer={
          <ChatComposerDrawer count={2} label="Sources" defaultIsCollapsed>
            <div className="flex min-w-0 flex-wrap items-center gap-2" aria-label="Connected sources">
              {sources.map((source, index) => (
                <Citation key={source.label} source={{ title: source.label }} number={index + 1} variant="label" />
              ))}
            </div>
          </ChatComposerDrawer>
        }
        input={
          <ChatComposerInput
            label="Chat message"
            maxRows={7}
            value={value}
            onChange={setValue}
          />
        }
        footerActions={<span className="text-xs text-black/42">Enter to send</span>}
        sendButton={
          <>
            <span id={labelId} className="sr-only">Send message</span>
            <ChatSendButton
              aria-labelledby={labelId}
              size="sm"
              onClick={submit}
              className="!rounded-full rounded-full bg-primary text-primary-foreground hover:bg-[#303036] active:bg-black disabled:bg-black/25"
            />
          </>
        }
        className="mx-auto max-w-[720px]"
        style={{
          "--_chat-composer-radius": "12px",
          "--_chat-composer-padding": "10px",
        } as CSSProperties}
      />
    </div>
  );
}

function LandingSourcesPopover({
  open,
  onOpenChange,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  return (
    <div className="relative">
      <button
        type="button"
        aria-haspopup="dialog"
        aria-expanded={open}
        onClick={() => onOpenChange(!open)}
        className="inline-flex min-h-10 items-center gap-2 rounded-lg border border-black/10 bg-white px-3 text-sm font-semibold text-black/70 transition hover:border-black/20 hover:bg-black/[0.03] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-400 focus-visible:ring-offset-2"
      >
        <ExternalLink aria-hidden="true" className="size-4" />
        Sources · 2
      </button>

      {open ? (
        <div
          role="dialog"
          aria-modal="true"
          aria-label="Sources"
          className="fixed inset-x-3 bottom-3 z-50 max-h-[min(70vh,34rem)] overflow-y-auto rounded-2xl border border-black/12 bg-white p-4 text-left shadow-[0_24px_80px_rgba(0,0,0,0.2)] sm:absolute sm:left-0 sm:top-[calc(100%+10px)] sm:bottom-auto sm:w-[360px]"
        >
          <div className="flex items-start gap-3">
            <div className="min-w-0 flex-1">
              <h4 className="text-sm font-semibold text-black/85">Sources</h4>
              <p className="mt-1 text-xs leading-5 text-black/50">Sources attached to this example artifact.</p>
            </div>
            <button
              type="button"
              onClick={() => onOpenChange(false)}
              aria-label="Close sources"
              className="inline-flex size-9 shrink-0 items-center justify-center rounded-lg text-black/50 hover:bg-black/5"
            >
              <X aria-hidden="true" className="size-4" />
            </button>
          </div>
          <ol className="mt-4 space-y-2" aria-label="Current sources">
            {sources.map((source, index) => (
              <li key={source.label} className="rounded-xl border border-black/[0.08] px-3 py-2.5">
                <Citation source={{ title: source.label }} number={index + 1} variant="label" />
              </li>
            ))}
          </ol>
        </div>
      ) : null}
    </div>
  );
}
