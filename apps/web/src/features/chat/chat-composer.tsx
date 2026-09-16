"use client";

import { useEffect, useRef, useState } from "react";
import type { ChatModel, Skill } from "@plot/api-client";
import { plotApiClient } from "@/lib/api-client";
import PromptBar from "@/components/primitives/prompt-bar";
import { CHAT_MODELS, CHAT_MODEL_STORAGE_KEY, parseChatModel } from "./chat-models";
import { resolveComposerReferenceIds } from "./chat-workspace-utils";

type ChatComposerProps = {
  onSubmit: (message: string, referenceIds: string[], skillIds: string[], model: ChatModel) => void;
  variant?: "center" | "dock";
  id?: string;
  placeholder?: string;
  references?: { id: string; label: string; available: boolean; groupId?: string; url?: string }[];
  busy?: boolean;
  canGenerate?: boolean;
};

export function ChatComposer({
  onSubmit,
  variant = "dock",
  id,
  placeholder,
  references = [],
  busy = false,
  canGenerate = true,
}: ChatComposerProps) {
  const isCenter = variant === "center";
  const submittingRef = useRef(false);
  const [skills, setSkills] = useState<Skill[]>([]);
  const [skillIds, setSkillIds] = useState<string[]>([]);
  const [model, setModel] = useState<ChatModel>("auto");
  const [modelPreferenceLoaded, setModelPreferenceLoaded] = useState(false);
  const modelChangedRef = useRef(false);
  const isSendDisabled = busy || !canGenerate;

  useEffect(() => {
    const controller = new AbortController();
    plotApiClient
      .listSkills({ signal: controller.signal })
      .then((items) => {
        if (!controller.signal.aborted) {
          setSkills(items);
        }
      })
      .catch(() => {});

    function workspaceChanged() {
      controller.abort();
      setSkillIds([]);
      plotApiClient.listSkills().then(setSkills).catch(() => {});
    }

    window.addEventListener("plot:workspace-changed", workspaceChanged);
    return () => {
      controller.abort();
      window.removeEventListener("plot:workspace-changed", workspaceChanged);
    };
  }, []);

  useEffect(() => {
    let active = true;
    queueMicrotask(() => {
      if (!active) return;
      try {
        if (!modelChangedRef.current) {
          setModel(parseChatModel(window.localStorage.getItem(CHAT_MODEL_STORAGE_KEY)) ?? "auto");
        }
      } catch {
        if (!modelChangedRef.current) setModel("auto");
      } finally {
        setModelPreferenceLoaded(true);
      }
    });
    return () => {
      active = false;
    };
  }, []);

  useEffect(() => {
    if (!modelPreferenceLoaded) return;
    try {
      window.localStorage.setItem(CHAT_MODEL_STORAGE_KEY, model);
    } catch {
      // Storage can be unavailable in private browsing; the in-memory selection still works.
    }
  }, [model, modelPreferenceLoaded]);

  function handleSend(text: string) {
    if (submittingRef.current || isSendDisabled) return;
    const trimmed = text.trim();
    if (!trimmed) return;

    submittingRef.current = true;
    onSubmit(trimmed, resolveComposerReferenceIds(references, []), skillIds, model);
    setSkillIds([]);
    queueMicrotask(() => {
      submittingRef.current = false;
    });
  }

  const sendBtnClass =
    "bg-primary text-primary-foreground dark:bg-[#f4f4f5] dark:text-[#18181b] dark:hover:bg-white dark:active:bg-white disabled:opacity-30 dark:disabled:opacity-20";

  if (isCenter) {
    return (
      <div id={id} className="w-full">
        <div className="w-full">
          <PromptBar
            demo={false}
            tall
            variant="Rounded"
            modelPlacement="bottom"
            placeholder={placeholder || "Describe the update you need..."}
            ariaLabel="Chat message"
            sendLabel="Send message"
            disabled={isSendDisabled}
            sendButtonClassName={sendBtnClass}
            skills={skills}
            selectedSkillIds={skillIds}
            onSelectedSkillIdsChange={setSkillIds}
            models={CHAT_MODELS}
            selectedModelId={model}
            onSelectedModelIdChange={(value) => {
              modelChangedRef.current = true;
              setModel(parseChatModel(value) ?? "auto");
            }}
            onSend={handleSend}
          />
        </div>
      </div>
    );
  }

  return (
    <div
      id={id}
      className="w-full bg-[#fbfbf8]/95 px-4 pb-4 pt-3 backdrop-blur-xl dark:bg-[#111113]/95 sm:px-6"
    >
      <div className="mx-auto max-w-[720px]">
        <PromptBar
          demo={false}
          tall={false}
          variant="Pill"
          modelPlacement="top"
          placeholder={placeholder || "Ask Plot anything..."}
          ariaLabel="Chat message"
          sendLabel="Send message"
          disabled={isSendDisabled}
          sendButtonClassName={sendBtnClass}
          skills={skills}
          selectedSkillIds={skillIds}
          onSelectedSkillIdsChange={setSkillIds}
          models={CHAT_MODELS}
          selectedModelId={model}
          onSelectedModelIdChange={(value) => {
            modelChangedRef.current = true;
            setModel(parseChatModel(value) ?? "auto");
          }}
          onSend={handleSend}
        />
      </div>
    </div>
  );
}

export { PromptBar };
export default PromptBar;
