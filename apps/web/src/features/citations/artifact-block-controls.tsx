"use client";

import type { Editor } from "@tiptap/core";
import { useEditorState } from "@tiptap/react";
import { ArrowDown, ArrowUp, GripVertical, Heading1, Heading2, Heading3, List, ListOrdered, Plus, Trash2, Type, Copy } from "lucide-react";
import { useCallback, useEffect, useRef, useState, type PointerEvent as ReactPointerEvent, type RefObject } from "react";
import { createPortal } from "react-dom";

type BlockKind = "paragraph" | "heading1" | "heading2" | "heading3" | "bulletList" | "orderedList";

const BLOCKS = [
  { kind: "paragraph", label: "Text", hint: "Plain text", icon: Type },
  { kind: "heading1", label: "Heading 1", hint: "Large section title", icon: Heading1 },
  { kind: "heading2", label: "Heading 2", hint: "Section title", icon: Heading2 },
  { kind: "heading3", label: "Heading 3", hint: "Small section title", icon: Heading3 },
  { kind: "bulletList", label: "Bulleted list", hint: "List of ideas", icon: List },
  { kind: "orderedList", label: "Numbered list", hint: "Steps in order", icon: ListOrdered },
] as const;

type SlashState = { from: number; to: number; query: string; x: number; y: number };
type DragState = { from: number; boundary: number | null; lineTop: number | null; x: number; y: number; preview: string };
type DragSession = { from: number; pointerId: number; startX: number; startY: number; started: boolean; boundary: number | null };

export function ArtifactBlockControls({ editor, wrapperRef, showGutter = true }: { editor: Editor; wrapperRef: RefObject<HTMLDivElement | null>; showGutter?: boolean }) {
  const [slash, setSlash] = useState<SlashState | null>(null);
  const slashRef = useRef<SlashState | null>(null);
  const dismissedFrom = useRef<number | null>(null);
  const [selected, setSelected] = useState(0);
  const [hoverIndex, setHoverIndex] = useState<number | null>(null);
  const [menuIndex, setMenuIndex] = useState<number | null>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const [railTop, setRailTop] = useState<number | null>(null);
  const [drag, setDrag] = useState<DragState | null>(null);
  const dragRef = useRef<DragSession | null>(null);
  const suppressClickRef = useRef(false);
  const blockState = useEditorState({
    editor,
    selector: ({ editor: current }) => ({
      index: topLevelBlockIndex(current),
      count: current.state.doc.childCount,
    }),
  });
  const activeIndex = drag?.from ?? menuIndex ?? hoverIndex ?? blockState.index;
  const filtered = BLOCKS.filter((item) => `${item.label} ${item.hint} ${item.kind}`.toLowerCase().includes(slash?.query.toLowerCase() ?? ""));

  const syncSlash = useCallback(() => {
    if (editor.isDestroyed || !editor.isEditable || !editor.isFocused || !editor.state.selection.empty) {
      slashRef.current = null;
      setSlash(null);
      return;
    }
    const { $from } = editor.state.selection;
    if ($from.parent.type.name !== "paragraph") {
      slashRef.current = null;
      setSlash(null);
      return;
    }
    const value = $from.parent.textBetween(0, $from.parentOffset, undefined, "\ufffc");
    if (!/^\/[a-z\d ]{0,32}$/i.test(value)) {
      dismissedFrom.current = null;
      slashRef.current = null;
      setSlash(null);
      return;
    }
    const from = $from.pos - value.length;
    if (dismissedFrom.current === from) return;
    const caret = editor.view.coordsAtPos($from.pos);
    const next = {
      from,
      to: $from.pos,
      query: value.slice(1),
      x: Math.max(8, Math.min(caret.left, window.innerWidth - 296)),
      y: caret.bottom + 8 + 284 < window.innerHeight ? caret.bottom + 8 : Math.max(8, caret.top - 292),
    };
    slashRef.current = next;
    setSlash((current) => current && current.from === next.from && current.to === next.to && current.x === next.x && current.y === next.y ? current : next);
    setSelected(0);
  }, [editor]);

  const runSlash = useCallback((kind: BlockKind) => {
    const match = slashRef.current;
    if (!match) return;
    slashRef.current = null;
    setSlash(null);
    const chain = editor.chain().focus().deleteRange({ from: match.from, to: match.to });
    if (kind === "paragraph") chain.setParagraph().run();
    else if (kind === "heading1") chain.setHeading({ level: 1 }).run();
    else if (kind === "heading2") chain.setHeading({ level: 2 }).run();
    else if (kind === "heading3") chain.setHeading({ level: 3 }).run();
    else if (kind === "bulletList") chain.toggleBulletList().run();
    else chain.toggleOrderedList().run();
  }, [editor]);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      const match = slashRef.current;
      if (!match) return;
      const items = BLOCKS.filter((item) => `${item.label} ${item.hint} ${item.kind}`.toLowerCase().includes(match.query.toLowerCase()));
      if (event.key === "ArrowDown" || event.key === "ArrowUp") {
        event.preventDefault();
        setSelected((index) => (index + (event.key === "ArrowDown" ? 1 : -1) + items.length) % (items.length || 1));
      } else if (event.key === "Enter" && items.length) {
        event.preventDefault();
        runSlash(items[Math.min(selected, items.length - 1)].kind);
      } else if (event.key === "Escape") {
        event.preventDefault();
        dismissedFrom.current = match.from;
        slashRef.current = null;
        setSlash(null);
      }
    };
    editor.on("update", syncSlash);
    editor.on("selectionUpdate", syncSlash);
    editor.on("focus", syncSlash);
    editor.on("blur", syncSlash);
    editor.view.dom.addEventListener("keydown", onKeyDown, true);
    document.addEventListener("scroll", syncSlash, true);
    window.addEventListener("resize", syncSlash);
    return () => {
      editor.off("update", syncSlash);
      editor.off("selectionUpdate", syncSlash);
      editor.off("focus", syncSlash);
      editor.off("blur", syncSlash);
      editor.view.dom.removeEventListener("keydown", onKeyDown, true);
      document.removeEventListener("scroll", syncSlash, true);
      window.removeEventListener("resize", syncSlash);
    };
  }, [editor, runSlash, selected, syncSlash]);

  useEffect(() => {
    const root = editor.view.dom;
    const wrapper = wrapperRef.current;
    const onMove = (event: PointerEvent) => {
      let element = event.target instanceof HTMLElement ? event.target : null;
      while (element && element.parentElement !== root) element = element.parentElement;
      if (!element) return;
      const index = Array.from(root.children).indexOf(element);
      if (index >= 0) setHoverIndex((current) => current === index ? current : index);
    };
    root.addEventListener("pointermove", onMove);
    const onLeave = () => setHoverIndex(null);
    wrapper?.addEventListener("pointerleave", onLeave);
    return () => {
      root.removeEventListener("pointermove", onMove);
      wrapper?.removeEventListener("pointerleave", onLeave);
    };
  }, [editor, wrapperRef]);

  useEffect(() => {
    if (menuIndex === null) return;
    const close = (event: PointerEvent) => {
      const target = event.target;
      if (!(target instanceof Node) || (!menuRef.current?.contains(target) && !(target instanceof Element && target.closest('button[aria-label="Block menu"]')))) setMenuIndex(null);
    };
    const escape = (event: KeyboardEvent) => {
      if (event.key === "Escape") setMenuIndex(null);
    };
    document.addEventListener("pointerdown", close);
    document.addEventListener("keydown", escape);
    return () => {
      document.removeEventListener("pointerdown", close);
      document.removeEventListener("keydown", escape);
    };
  }, [menuIndex]);

  useEffect(() => {
    const update = () => {
      const wrapper = wrapperRef.current;
      if (!showGutter || !wrapper || activeIndex < 0 || activeIndex >= editor.state.doc.childCount) {
        setRailTop(null);
        return;
      }
      const block = editor.view.nodeDOM(blockPosition(editor, activeIndex));
      if (!(block instanceof HTMLElement)) {
        setRailTop(null);
        return;
      }
      setRailTop(block.getBoundingClientRect().top - wrapper.getBoundingClientRect().top);
    };
    update();
    editor.on("update", update);
    document.addEventListener("scroll", update, true);
    window.addEventListener("resize", update);
    return () => {
      editor.off("update", update);
      document.removeEventListener("scroll", update, true);
      window.removeEventListener("resize", update);
    };
  }, [activeIndex, editor, showGutter, wrapperRef]);

  const focusBlock = (index: number) => {
    const position = blockPosition(editor, index);
    editor.chain().focus().setTextSelection(position + 1).run();
  };

  const runBlock = (action: BlockKind | "duplicate" | "delete" | "up" | "down") => {
    const index = menuIndex ?? activeIndex;
    if (index < 0 || index >= editor.state.doc.childCount) return;
    setMenuIndex(null);
    if (action === "duplicate") {
      const node = editor.state.doc.child(index);
      editor.chain().focus().insertContentAt(blockPosition(editor, index) + node.nodeSize, node.toJSON()).run();
      return;
    }
    if (action === "delete") {
      if (editor.state.doc.childCount > 1) {
        const from = blockPosition(editor, index);
        editor.chain().focus().deleteRange({ from, to: from + editor.state.doc.child(index).nodeSize }).run();
      }
      return;
    }
    if (action === "up" || action === "down") {
      moveBlockAtIndex(editor, index, action === "up" ? -1 : 1);
      return;
    }
    focusBlock(index);
    if (action === "paragraph" || action.startsWith("heading")) {
      if (editor.isActive("listItem")) editor.chain().focus().liftListItem("listItem").run();
      if (action === "paragraph") editor.chain().focus().setParagraph().run();
      else editor.chain().focus().setHeading({ level: Number(action.slice(-1)) as 1 | 2 | 3 }).run();
    } else if (action === "bulletList" && !editor.isActive("bulletList")) {
      editor.chain().focus().toggleBulletList().run();
    } else if (action === "orderedList" && !editor.isActive("orderedList")) {
      editor.chain().focus().toggleOrderedList().run();
    }
  };

  const addBlock = () => {
    const index = hoverIndex ?? blockState.index;
    if (index < 0 || index >= editor.state.doc.childCount) return;
    const node = editor.state.doc.child(index);
    editor.chain().focus().insertContentAt(blockPosition(editor, index) + node.nodeSize, { type: "paragraph" }, { updateSelection: true }).run();
    setHoverIndex(null);
  };

  const clearDrag = () => {
    dragRef.current = null;
    setDrag(null);
  };

  const startDrag = (event: ReactPointerEvent<HTMLButtonElement>) => {
    if (event.button !== 0 || !editor.isEditable) return;
    event.preventDefault();
    const from = activeIndex;
    if (from < 0 || from >= editor.state.doc.childCount) return;
    event.currentTarget.setPointerCapture(event.pointerId);
    dragRef.current = { from, pointerId: event.pointerId, startX: event.clientX, startY: event.clientY, started: false, boundary: null };
  };

  const moveDrag = (event: ReactPointerEvent<HTMLButtonElement>) => {
    const session = dragRef.current;
    if (!session || session.pointerId !== event.pointerId) return;
    if (!session.started && Math.hypot(event.clientX - session.startX, event.clientY - session.startY) < 5) return;
    if (!session.started) {
      session.started = true;
      setMenuIndex(null);
    }
    const wrapper = wrapperRef.current;
    if (!wrapper) return;
    scrollNearEdge(wrapper, event.clientY);
    const wrapperRect = wrapper.getBoundingClientRect();
    const inside = event.clientX >= wrapperRect.left - 80 && event.clientX <= wrapperRect.right + 80;
    const target = inside ? dropBoundary(editor, event.clientY) : null;
    const boundary = target && target.boundary !== session.from && target.boundary !== session.from + 1 ? target.boundary : null;
    session.boundary = boundary;
    setDrag({
      from: session.from,
      boundary,
      lineTop: boundary === null || !target ? null : target.y - wrapperRect.top,
      x: event.clientX,
      y: event.clientY,
      preview: editor.state.doc.child(session.from).textContent.trim().slice(0, 80) || "Empty block",
    });
  };

  const finishDrag = (event: ReactPointerEvent<HTMLButtonElement>) => {
    const session = dragRef.current;
    if (!session || session.pointerId !== event.pointerId) return;
    if (event.currentTarget.hasPointerCapture(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId);
    const boundary = session.boundary;
    const didDrag = session.started;
    clearDrag();
    if (didDrag) {
      suppressClickRef.current = true;
      window.setTimeout(() => { suppressClickRef.current = false; }, 0);
      if (boundary !== null) moveBlockToBoundary(editor, session.from, boundary);
    }
  };

  return (
    <>
      {railTop !== null ? (
        <div className={`absolute -left-[46px] z-20 flex items-center transition-opacity ${drag ? "opacity-100" : "opacity-0 group-hover/editor:opacity-100 group-focus-within/editor:opacity-100"}`} style={{ top: railTop }}>
          <button type="button" aria-label="Add block below" title="Add block below" onPointerDown={(event) => event.preventDefault()} onClick={addBlock} className="glass-button glass-icon flex size-6 items-center justify-center"><Plus className="size-4" /></button>
          <button type="button" aria-label="Block menu" title="Drag to move · Click for menu" aria-expanded={menuIndex !== null} onPointerDown={startDrag} onPointerMove={moveDrag} onPointerUp={finishDrag} onPointerCancel={clearDrag} onClick={() => { if (suppressClickRef.current) return; setMenuIndex((current) => current === activeIndex ? null : activeIndex); }} className="glass-button glass-icon flex size-6 touch-none cursor-grab items-center justify-center active:cursor-grabbing"><GripVertical className="size-4" /></button>
        </div>
      ) : null}
      {drag?.lineTop !== null && drag?.lineTop !== undefined ? <div aria-hidden="true" className="pointer-events-none absolute inset-x-0 z-30 h-0.5 rounded-full bg-black/35 dark:bg-white/45" style={{ top: drag.lineTop }} /> : null}
      {drag && createPortal(
        <div aria-hidden="true" className="glass-layer pointer-events-none fixed z-50 flex max-w-72 items-center gap-2 rounded-md border border-black/10 px-3 py-2 text-xs text-black/70 dark:border-white/15 dark:text-white/80" style={{ left: Math.min(drag.x + 12, window.innerWidth - 300), top: Math.min(drag.y + 12, window.innerHeight - 52) }}><GripVertical className="size-4 shrink-0" /><span className="truncate">{drag.preview}</span></div>, document.body,
      )}
      {menuIndex !== null && railTop !== null ? (
        <div ref={menuRef} role="menu" aria-label="Block menu" className="glass-layer absolute left-0 z-40 max-h-80 w-56 overflow-y-auto rounded-lg border border-black/10 p-1.5 dark:border-white/15" style={{ top: railTop + 28 }} onPointerDown={(event) => event.preventDefault()}>
          {BLOCKS.map(({ kind, label, icon: Icon }) => <button key={kind} type="button" role="menuitem" onClick={() => runBlock(kind)} className="glass-control flex w-full items-center gap-2 rounded px-2 py-1.5 text-left text-xs"><Icon className="size-4" />{label}</button>)}
          <div className="my-1 border-t border-black/10 dark:border-white/10" />
          <button type="button" role="menuitem" onClick={() => runBlock("duplicate")} className="glass-control flex w-full items-center gap-2 rounded px-2 py-1.5 text-left text-xs"><Copy className="size-4" />Duplicate</button>
          <button type="button" role="menuitem" disabled={menuIndex === 0} onClick={() => runBlock("up")} className="glass-control flex w-full items-center gap-2 rounded px-2 py-1.5 text-left text-xs"><ArrowUp className="size-4" />Move up</button>
          <button type="button" role="menuitem" disabled={menuIndex >= blockState.count - 1} onClick={() => runBlock("down")} className="glass-control flex w-full items-center gap-2 rounded px-2 py-1.5 text-left text-xs"><ArrowDown className="size-4" />Move down</button>
          <button type="button" role="menuitem" disabled={blockState.count <= 1} onClick={() => runBlock("delete")} className="glass-control glass-danger flex w-full items-center gap-2 rounded px-2 py-1.5 text-left text-xs"><Trash2 className="size-4" />Delete</button>
        </div>
      ) : null}
      {slash && createPortal(
        <div role="listbox" aria-label="Block commands" className="glass-layer fixed z-50 max-h-[284px] w-72 overflow-y-auto rounded-lg border border-black/10 p-1.5 dark:border-white/15" style={{ left: slash.x, top: slash.y }} onPointerDown={(event) => event.preventDefault()}>
          {filtered.length ? filtered.map(({ kind, label, hint, icon: Icon }, index) => (
            <button key={kind} type="button" role="option" aria-selected={selected === index} onMouseEnter={() => setSelected(index)} onClick={() => runSlash(kind)} className="glass-control flex w-full items-center gap-2 rounded px-2 py-2 text-left text-xs">
              <Icon className="size-4 shrink-0 text-black/45 dark:text-white/55" />
              <span><span className="block font-medium">{label}</span><span className="block text-black/45 dark:text-white/45">{hint}</span></span>
            </button>
          )) : <p className="px-2 py-3 text-xs text-black/45 dark:text-white/45">No matching blocks</p>}
        </div>, document.body,
      )}
    </>
  );
}

export function topLevelBlockIndex(editor: Editor): number {
  return editor.state.selection.$from.index(0);
}

export function moveBlockAtIndex(editor: Editor, index: number, direction: -1 | 1): void {
  moveBlockToBoundary(editor, index, direction < 0 ? index - 1 : index + 2);
}

export function moveBlockToBoundary(editor: Editor, index: number, boundary: number): void {
  const nodes = editor.state.doc.content.content.slice();
  if (index < 0 || index >= nodes.length || boundary < 0 || boundary > nodes.length || boundary === index || boundary === index + 1) return;
  const [block] = nodes.splice(index, 1);
  const target = boundary > index ? boundary - 1 : boundary;
  nodes.splice(target, 0, block);
  const start = nodes.slice(0, target).reduce((position, node) => position + node.nodeSize, 0);
  const reordered = editor.state.schema.topNodeType.create(null, nodes);
  editor.chain().focus().command(({ tr }) => {
    tr.replaceWith(0, editor.state.doc.content.size, reordered.content);
    let statementNumber = 0;
    const citations: Array<{ position: number; attrs: Record<string, unknown> }> = [];
    tr.doc.descendants((node, position) => {
      if (["paragraph", "heading", "cta"].includes(node.type.name)) statementNumber += 1;
      if (node.type.name === "citation" && node.attrs.number !== statementNumber) {
        citations.push({ position, attrs: { ...node.attrs, number: statementNumber } });
      }
    });
    for (const citation of citations) tr.setNodeMarkup(citation.position, undefined, citation.attrs);
    return true;
  }).setTextSelection(start + 1).run();
}

function dropBoundary(editor: Editor, y: number): { boundary: number; y: number } | null {
  const rects = Array.from({ length: editor.state.doc.childCount }, (_, index) => {
    const node = editor.view.nodeDOM(blockPosition(editor, index));
    return node instanceof HTMLElement ? node.getBoundingClientRect() : null;
  });
  if (rects.some((rect) => rect === null)) return null;
  const blocks = rects as DOMRect[];
  let boundary = blocks.length;
  for (let index = 0; index < blocks.length; index += 1) {
    if (y < (blocks[index].top + blocks[index].bottom) / 2) {
      boundary = index;
      break;
    }
  }
  const lineY = boundary === 0 ? blocks[0].top - 5
    : boundary === blocks.length ? blocks[blocks.length - 1].bottom + 5
      : (blocks[boundary - 1].bottom + blocks[boundary].top) / 2;
  return { boundary, y: lineY };
}

function scrollNearEdge(wrapper: HTMLElement, y: number): void {
  let element: HTMLElement | null = wrapper.parentElement;
  while (element && !(element.scrollHeight > element.clientHeight && /auto|scroll/.test(getComputedStyle(element).overflowY))) element = element.parentElement;
  if (!element) return;
  const rect = element.getBoundingClientRect();
  if (y < rect.top + 48) element.scrollBy(0, -20);
  else if (y > rect.bottom - 48) element.scrollBy(0, 20);
}

function blockPosition(editor: Editor, index: number): number {
  let position = 0;
  for (let current = 0; current < index; current += 1) position += editor.state.doc.child(current).nodeSize;
  return position;
}
