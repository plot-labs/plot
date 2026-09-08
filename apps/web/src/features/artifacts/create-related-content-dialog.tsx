"use client";

import { AlertCircle, FileText, Loader2, Sparkles, X } from "lucide-react";
import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";

import { PlotApiError, type Artifact, type ContentType, type PlotApiClient } from "@plot/api-client";

type CreateRelatedContentDialogProps = {
  pack: Artifact;
  client: PlotApiClient;
  open: boolean;
  onClose: () => void;
};

export function CreateRelatedContentDialog({
  pack,
  client,
  open,
  onClose,
}: CreateRelatedContentDialogProps) {
  const router = useRouter();
  const defaultTargetType: ContentType =
    pack.contentType === "CHANGELOG" ? "LAUNCH_ANNOUNCEMENT" : "CHANGELOG";
  const [targetType, setTargetType] = useState<ContentType>(defaultTargetType);
  const [instruction, setInstruction] = useState("");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const dialogRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    setTargetType(pack.contentType === "CHANGELOG" ? "LAUNCH_ANNOUNCEMENT" : "CHANGELOG");
    setInstruction("");
    setError(null);
    setPending(false);
  }, [open, pack.contentType]);

  useEffect(() => {
    if (!open) return;

    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        event.preventDefault();
        onClose();
      }
    }

    document.addEventListener("keydown", handleKeyDown);
    return () => document.removeEventListener("keydown", handleKeyDown);
  }, [open, onClose]);

  if (!open) return null;

  async function handleReplicate(e: React.FormEvent) {
    e.preventDefault();
    if (pending) return;
    setPending(true);
    setError(null);

    const idempotencyKey = `replicate-${pack.id}-${targetType}-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;
    try {
      const run = await client.replicateArtifact(
        pack.id,
        {
          contentType: targetType,
          instruction: instruction.trim() || undefined,
        },
        idempotencyKey,
      );
      onClose();
      router.push(`/chat?chat=${run.chatId}&agent=${run.id}`);
    } catch (err) {
      setPending(false);
      if (err instanceof PlotApiError) {
        if (err.code === "SOURCE_NOT_READY") {
          setError("연결된 소스 저장소 또는 권한이 더 이상 활성 상태가 아닙니다.");
        } else {
          setError(err.message || "새로운 콘텐츠 생성 요청 중 오류가 발생했습니다.");
        }
      } else {
        setError("오류가 발생했습니다. 잠시 후 다시 시도해주세요.");
      }
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div
        className="fixed inset-0 bg-black/40 backdrop-blur-sm transition-opacity"
        onClick={onClose}
        aria-hidden="true"
      />
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="replicate-dialog-title"
        className="relative z-10 w-full max-w-lg rounded-xl border border-black/10 bg-white p-6 shadow-2xl dark:border-white/10 dark:bg-[#1f1f23]"
      >
        <header className="flex items-start justify-between gap-4">
          <div className="flex items-center gap-2.5">
            <div className="flex size-9 items-center justify-center rounded-lg bg-amber-500/10 text-amber-600 dark:bg-amber-400/10 dark:text-amber-400">
              <Sparkles className="size-5" />
            </div>
            <div>
              <h2
                id="replicate-dialog-title"
                className="text-base font-semibold text-black/90 dark:text-white/90"
              >
                이 근거로 다른 콘텐츠 만들기
              </h2>
              <p className="text-xs text-black/50 dark:text-white/50">
                현재 문서에 검증된 근거 번들을 그대로 재사용하여 새로운 형식의 콘텐츠를 생성합니다.
              </p>
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            className="rounded-lg p-1 text-black/40 hover:bg-black/5 hover:text-black/70 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-black/15 dark:text-white/40 dark:hover:bg-white/10 dark:hover:text-white/70"
          >
            <X className="size-4" />
          </button>
        </header>

        <form onSubmit={handleReplicate} className="mt-5 space-y-4">
          <div>
            <label className="block text-xs font-medium text-black/70 dark:text-white/70">
              생성할 콘텐츠 유형
            </label>
            <div className="mt-2 grid grid-cols-2 gap-2.5">
              <button
                type="button"
                onClick={() => setTargetType("LAUNCH_ANNOUNCEMENT")}
                className={`flex flex-col items-start rounded-lg border p-3 text-left transition ${
                  targetType === "LAUNCH_ANNOUNCEMENT"
                    ? "border-amber-500/80 bg-amber-500/5 ring-1 ring-amber-500/30 dark:border-amber-400/80 dark:bg-amber-400/10"
                    : "border-black/10 hover:border-black/20 dark:border-white/10 dark:hover:border-white/20"
                }`}
              >
                <span className="text-xs font-semibold text-black/85 dark:text-white/85">
                  출시 공지
                </span>
                <span className="mt-1 text-[11px] text-black/50 dark:text-white/50">
                  고객 대상 공지문 및 마케팅 노트
                </span>
              </button>

              <button
                type="button"
                onClick={() => setTargetType("CHANGELOG")}
                className={`flex flex-col items-start rounded-lg border p-3 text-left transition ${
                  targetType === "CHANGELOG"
                    ? "border-amber-500/80 bg-amber-500/5 ring-1 ring-amber-500/30 dark:border-amber-400/80 dark:bg-amber-400/10"
                    : "border-black/10 hover:border-black/20 dark:border-white/10 dark:hover:border-white/20"
                }`}
              >
                <span className="text-xs font-semibold text-black/85 dark:text-white/85">
                  변경 로그
                </span>
                <span className="mt-1 text-[11px] text-black/50 dark:text-white/50">
                  기술 및 릴리스 중심의 세부 기록
                </span>
              </button>
            </div>
          </div>

          <div>
            <label
              htmlFor="replicate-instruction"
              className="block text-xs font-medium text-black/70 dark:text-white/70"
            >
              추가 지시사항 (선택사항)
            </label>
            <textarea
              id="replicate-instruction"
              value={instruction}
              onChange={(e) => setInstruction(e.target.value)}
              placeholder="예: 사용자 관점에서 주요 개선점을 강조하고 간결하게 작성해주세요."
              rows={3}
              maxLength={2000}
              className="mt-1.5 w-full rounded-lg border border-black/15 bg-transparent px-3 py-2 text-xs text-black/85 placeholder:text-black/35 focus:border-amber-500 focus:outline-none focus:ring-1 focus:ring-amber-500 dark:border-white/15 dark:text-white/85 dark:placeholder:text-white/35"
            />
          </div>

          <div className="rounded-lg border border-blue-500/20 bg-blue-500/5 p-3 dark:border-blue-400/20 dark:bg-blue-400/10">
            <div className="flex items-start gap-2 text-blue-600 dark:text-blue-400">
              <AlertCircle className="mt-0.5 size-4 shrink-0" />
              <div className="text-[12px] leading-relaxed">
                <p className="font-medium">생성 크레딧 차감 안내</p>
                <p className="mt-0.5 text-blue-600/80 dark:text-blue-400/80">
                  새로운 생성을 시작하며 1회 생성 한도가 차감됩니다. 기존 아티팩트는 그대로 유지됩니다.
                </p>
              </div>
            </div>
          </div>

          {error ? (
            <div className="rounded-lg border border-rose-500/20 bg-rose-500/5 p-3 text-xs text-rose-600 dark:border-rose-400/20 dark:bg-rose-400/10 dark:text-rose-400">
              {error}
            </div>
          ) : null}

          <footer className="mt-6 flex items-center justify-end gap-2.5 pt-2">
            <button
              type="button"
              onClick={onClose}
              disabled={pending}
              className="rounded-lg border border-black/15 px-3.5 py-2 text-xs font-medium text-black/70 hover:bg-black/5 disabled:opacity-50 dark:border-white/15 dark:text-white/70 dark:hover:bg-white/5"
            >
              취소
            </button>
            <button
              type="submit"
              disabled={pending}
              className="inline-flex items-center gap-1.5 rounded-lg bg-black px-4 py-2 text-xs font-medium text-white transition hover:bg-black/80 disabled:opacity-50 dark:bg-white dark:text-black dark:hover:bg-white/90"
            >
              {pending ? (
                <>
                  <Loader2 className="size-3.5 animate-spin" />
                  생성 중…
                </>
              ) : (
                <>
                  <Sparkles className="size-3.5" />
                  생성 시작
                </>
              )}
            </button>
          </footer>
        </form>
      </div>
    </div>
  );
}
