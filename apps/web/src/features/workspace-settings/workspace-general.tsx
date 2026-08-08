"use client";

import { ImageAdd01Icon } from "@hugeicons/core-free-icons";
import { HugeiconsIcon } from "@hugeicons/react";
import Image from "next/image";
import { useEffect, useRef, useState } from "react";
import { LoaderCircle, Trash2 } from "lucide-react";

import { getSelectedWorkspaceId, plotApiClient, type WorkspaceSummary } from "@/lib/api-client";

const maxLogoBytes = 400_000;

export function WorkspaceGeneral() {
  const workspaceId = getSelectedWorkspaceId();
  const [workspace, setWorkspace] = useState<WorkspaceSummary | null>(null);
  const [name, setName] = useState("");
  const [savedName, setSavedName] = useState("");
  const [logoUrl, setLogoUrl] = useState<string | null>(null);
  const [savedLogoUrl, setSavedLogoUrl] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(() => Boolean(workspaceId));
  const [isSaving, setIsSaving] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(() => workspaceId ? null : "Select a workspace to edit its settings.");
  const fileInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    let cancelled = false;
    if (!workspaceId) {
      return () => { cancelled = true; };
    }

    plotApiClient.getWorkspace(workspaceId)
      .then((value) => {
        if (cancelled) return;
        const nextLogoUrl = value.logoUrl ?? null;
        setWorkspace(value);
        setName(value.name);
        setSavedName(value.name);
        setLogoUrl(nextLogoUrl);
        setSavedLogoUrl(nextLogoUrl);
      })
      .catch(() => {
        if (!cancelled) setError("Workspace settings could not be loaded.");
      })
      .finally(() => {
        if (!cancelled) setIsLoading(false);
      });

    return () => { cancelled = true; };
  }, [workspaceId]);

  const dirty = name.trim() !== savedName || logoUrl !== savedLogoUrl;
  const canEdit = workspace?.role === "OWNER";

  const onLogoSelected = (file: File | undefined) => {
    if (!file) return;
    setMessage(null);
    setError(null);
    if (!file.type.startsWith("image/")) {
      setError("Choose an image file.");
      return;
    }
    if (file.size > maxLogoBytes) {
      setError("Choose an image smaller than 400 KB.");
      return;
    }
    const reader = new FileReader();
    reader.onload = () => {
      if (typeof reader.result === "string") setLogoUrl(reader.result);
    };
    reader.readAsDataURL(file);
  };

  const save = async () => {
    if (!workspace || !name.trim() || !dirty || isSaving) return;
    setIsSaving(true);
    setMessage(null);
    setError(null);
    try {
      const updated = await plotApiClient.updateWorkspace(workspace.id, {
        name: name.trim(),
        logoUrl: logoUrl ?? "",
      });
      const nextLogoUrl = updated.logoUrl ?? null;
      setWorkspace(updated);
      setName(updated.name);
      setSavedName(updated.name);
      setLogoUrl(nextLogoUrl);
      setSavedLogoUrl(nextLogoUrl);
      window.dispatchEvent(new CustomEvent("plot:workspace-updated", {
        detail: { id: updated.id, name: updated.name, logoUrl: nextLogoUrl },
      }));
      setMessage("Workspace settings saved.");
    } catch {
      setError("Workspace settings could not be saved.");
    } finally {
      setIsSaving(false);
    }
  };

  const mark = (name || workspace?.name || "W").slice(0, 1).toUpperCase();

  return (
    <div className="h-full overflow-y-auto bg-[#f4f6f8] px-5 py-8 dark:bg-[#101112] sm:px-8 sm:py-10 lg:px-10">
      <div className="mx-auto max-w-[760px] pb-16">
        <header className="max-w-[620px]">
          <h1 className="font-serif text-[32px] font-normal leading-[1.08] tracking-[-0.025em] text-black/90 dark:text-white/92 sm:text-[36px]">
            General
          </h1>
          <p className="mt-2 text-[14px] leading-6 text-black/52 dark:text-white/50">
            Manage your workspace identity and profile.
          </p>
        </header>

        <section className="mt-8 overflow-hidden rounded-[14px] border border-black/[0.09] bg-white shadow-[0_1px_2px_rgb(15_23_42_/_0.025)] dark:border-white/10 dark:bg-white/[0.045]" aria-labelledby="workspace-profile-heading">
          <div className="border-b border-black/[0.07] px-5 py-5 dark:border-white/[0.08] sm:px-6">
            <h2 id="workspace-profile-heading" className="text-[15px] font-semibold text-black/82 dark:text-white/86">Workspace profile</h2>
            <p className="mt-1 text-[13px] leading-5 text-black/48 dark:text-white/48">This is how your workspace appears across Plot.</p>
          </div>

          <div className="space-y-7 px-5 py-6 sm:px-6">
            {isLoading ? (
              <div className="flex items-center gap-2 text-sm text-black/45 dark:text-white/45" role="status">
                <LoaderCircle className="size-4 animate-spin" aria-hidden="true" />
                Loading workspace settings…
              </div>
            ) : (
              <>
                <div className="flex items-center gap-4">
                  <div className="flex size-16 shrink-0 items-center justify-center overflow-hidden rounded-[16px] bg-[#ef3f2c] font-serif text-2xl font-semibold text-white shadow-[0_4px_12px_rgb(239_63_44_/_0.18)]">
                    {logoUrl ? <Image src={logoUrl} alt="" width={64} height={64} unoptimized className="size-full object-cover" /> : mark}
                  </div>
                  <div className="min-w-0">
                    <p className="text-sm font-medium text-black/78 dark:text-white/82">Workspace logo</p>
                    <p className="mt-1 text-[13px] leading-5 text-black/45 dark:text-white/45">Use a square image up to 400 KB.</p>
                    <div className="mt-3 flex items-center gap-2">
                      <input
                        ref={fileInputRef}
                        type="file"
                        accept="image/png,image/jpeg,image/gif,image/webp"
                        className="sr-only"
                        onChange={(event) => onLogoSelected(event.target.files?.[0])}
                      />
                      <button
                        type="button"
                        onClick={() => fileInputRef.current?.click()}
                        disabled={!canEdit}
                        className="inline-flex h-8 items-center gap-2 rounded-[8px] border border-black/10 px-3 text-[13px] font-medium text-black/65 transition hover:bg-black/[0.04] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-black/20 dark:border-white/12 dark:text-white/65 dark:hover:bg-white/10 dark:focus-visible:ring-white/25"
                      >
                        <HugeiconsIcon icon={ImageAdd01Icon} size={15} color="currentColor" strokeWidth={1.5} aria-hidden="true" />
                        Upload image
                      </button>
                      {logoUrl && (
                        <button
                          type="button"
                          onClick={() => setLogoUrl(null)}
                          disabled={!canEdit}
                          aria-label="Remove workspace logo"
                          title="Remove workspace logo"
                          className="inline-flex size-8 items-center justify-center rounded-[8px] text-black/42 transition hover:bg-black/[0.04] hover:text-black/75 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-black/20 dark:text-white/45 dark:hover:bg-white/10 dark:hover:text-white/80 dark:focus-visible:ring-white/25"
                        >
                          <Trash2 className="size-4" aria-hidden="true" />
                        </button>
                      )}
                    </div>
                  </div>
                </div>

                <label className="block">
                  <span className="text-[13px] font-medium text-black/72 dark:text-white/76">Workspace name</span>
                  <input
                    value={name}
                    onChange={(event) => setName(event.target.value)}
                    disabled={!canEdit}
                    maxLength={80}
                    className="mt-2 flex h-10 w-full items-center rounded-[9px] border border-black/10 bg-white px-3 text-sm text-black/80 outline-none transition placeholder:text-black/35 focus:border-black/25 focus:ring-2 focus:ring-black/[0.04] dark:border-white/12 dark:bg-white/[0.04] dark:text-white/82 dark:placeholder:text-white/35 dark:focus:border-white/25 dark:focus:ring-white/[0.06]"
                  />
                </label>
              </>
            )}

            {error && <p role="alert" className="text-sm text-red-700 dark:text-red-300">{error}</p>}
            {!isLoading && workspace && !canEdit && <p role="status" className="text-sm text-black/48 dark:text-white/48">Only workspace owners can edit these settings.</p>}
            {message && <p role="status" className="text-sm text-black/55 dark:text-white/55">{message}</p>}

            <div className="flex justify-end border-t border-black/[0.07] pt-5 dark:border-white/[0.08]">
              <button
                type="button"
                onClick={() => { void save(); }}
                disabled={isLoading || isSaving || !canEdit || !dirty || !name.trim()}
                className="inline-flex h-9 items-center gap-2 rounded-[9px] bg-[#ef3f2c] px-4 text-sm font-semibold text-white shadow-[0_3px_10px_rgb(239_63_44_/_0.18)] transition hover:bg-[#dc3828] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#ef3f2c]/35 disabled:cursor-not-allowed disabled:opacity-45"
              >
                {isSaving && <LoaderCircle className="size-4 animate-spin" aria-hidden="true" />}
                Save changes
              </button>
            </div>
          </div>
        </section>
      </div>
    </div>
  );
}
