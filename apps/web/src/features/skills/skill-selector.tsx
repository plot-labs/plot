"use client";

import { useEffect, useEffectEvent, useRef, useState } from "react";
import type { Skill } from "@plot/api-client";
import { plotApiClient } from "@/lib/api-client";

export function SkillSelector({ value, onChange, disabled = false }: {
  value: string[];
  onChange: (ids: string[]) => void;
  disabled?: boolean;
}) {
  const [skills, setSkills] = useState<Skill[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [reload, setReload] = useState(0);
  const [creating, setCreating] = useState(false);
  const [saving, setSaving] = useState(false);
  const [draft, setDraft] = useState({ name: "", description: "", content: "" });
  const createRequest = useRef<AbortController | null>(null);

  const resetSelection = useEffectEvent(() => onChange([]));
  useEffect(() => {
    const controller = new AbortController();
    plotApiClient.listSkills({ signal: controller.signal }).then((items) => {
      if (!controller.signal.aborted) { setSkills(items); setLoading(false); setError(""); }
    }).catch(() => {
      if (!controller.signal.aborted) { setError("Skills could not be loaded."); setLoading(false); }
    });
    function workspaceChanged() {
      controller.abort();
      resetSelection();
      createRequest.current?.abort();
      setCreating(false);
      setSaving(false);
      setDraft({ name: "", description: "", content: "" });
      setSkills([]);
      setLoading(true);
      setError("");
      setReload((n) => n + 1);
    }
    window.addEventListener("plot:workspace-changed", workspaceChanged);
    return () => { controller.abort(); createRequest.current?.abort(); window.removeEventListener("plot:workspace-changed", workspaceChanged); };
  }, [reload]);

  async function createSkill() {
    if (saving || disabled || !draft.name || !draft.description.trim() || !draft.content.trim()) return;
    const controller = new AbortController();
    createRequest.current = controller;
    setSaving(true);
    setError("");
    try {
      const skill = await plotApiClient.createSkill(draft, { signal: controller.signal });
      if (controller.signal.aborted) return;
      setSkills((current) => [...current, { ...skill, isSystem: false }]);
      if (value.length < 4) onChange([...value, skill.id]);
      setDraft({ name: "", description: "", content: "" });
      setCreating(false);
    } catch (failure) {
      if (!controller.signal.aborted) setError(failure instanceof Error ? failure.message : "Skill could not be created.");
    } finally {
      if (!controller.signal.aborted) setSaving(false);
    }
  }

  return (
    <details className="relative text-sm">
      <summary className="cursor-pointer rounded-lg px-2 py-1.5 text-black/65 hover:bg-black/5 dark:text-white/65 dark:hover:bg-white/5">
        Skills{value.length ? ` · ${value.length} selected` : " · Optional"}
      </summary>
      <div className="my-2 max-h-64 min-w-64 overflow-auto rounded-xl border border-black/10 bg-white p-3 dark:border-white/10 dark:bg-[#1e1f23]">
        <p className="mb-2 text-xs text-black/50 dark:text-white/50">Choose up to 4 writing guides.</p>
        {loading ? <p role="status">Loading skills…</p> : null}
        {error ? <p role="alert">{error} <button type="button" disabled={saving} onClick={() => setReload((n) => n + 1)} className="underline">Retry</button></p> : null}
        {!loading && !error && !skills.length ? <p>No skills available.</p> : null}
        {skills.map((skill) => (
          <label key={skill.id} className="flex cursor-pointer items-start gap-2 rounded-lg p-2 hover:bg-black/5 dark:hover:bg-white/5">
            <input type="checkbox" className="mt-1" checked={value.includes(skill.id)}
              disabled={disabled || saving || (!value.includes(skill.id) && value.length >= 4)}
              onChange={(event) => onChange(event.target.checked ? [...value, skill.id] : value.filter((id) => id !== skill.id))} />
            <span><span className="block font-medium">{skill.name}</span><span className="block text-xs text-black/50 dark:text-white/50">{skill.description}</span></span>
          </label>
        ))}
        <button type="button" disabled={disabled || loading || saving} onClick={() => setCreating(!creating)}
          className="mt-2 rounded-lg px-2 py-1 text-sm underline disabled:opacity-50">{creating ? "Cancel" : "Create skill"}</button>
        {creating ? (
          <fieldset disabled={disabled || saving} className="mt-3 space-y-2 border-t border-black/10 pt-3 dark:border-white/10">
            <label className="block text-xs">Name
              <input aria-label="Skill name" value={draft.name} maxLength={64} placeholder="customer-update"
                onKeyDown={(event) => { if (event.key === "Enter") event.preventDefault(); }}
                onChange={(event) => setDraft({ ...draft, name: event.target.value })}
                className="mt-1 block w-full rounded border border-black/15 bg-transparent p-2 dark:border-white/20" />
            </label>
            <label className="block text-xs">When to use it
              <input value={draft.description} maxLength={500}
                onKeyDown={(event) => { if (event.key === "Enter") event.preventDefault(); }}
                onChange={(event) => setDraft({ ...draft, description: event.target.value })}
                className="mt-1 block w-full rounded border border-black/15 bg-transparent p-2 dark:border-white/20" />
            </label>
            <label className="block text-xs">Instructions
              <textarea value={draft.content} maxLength={16000} rows={5}
                onChange={(event) => setDraft({ ...draft, content: event.target.value })}
                className="mt-1 block w-full rounded border border-black/15 bg-transparent p-2 dark:border-white/20" />
            </label>
            <p className="text-xs text-black/50 dark:text-white/50">Use lowercase letters, numbers, and hyphens for the name.</p>
            <button type="button" onClick={() => void createSkill()}
              disabled={saving || !/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(draft.name) || !draft.description.trim() || !draft.content.trim()}
              className="rounded-lg bg-primary px-3 py-2 text-xs text-primary-foreground disabled:opacity-40">{saving ? "Saving…" : "Save skill"}</button>
          </fieldset>
        ) : null}
      </div>
    </details>
  );
}
