"use client";

import { useEffect, useRef, useState } from "react";
import type { ChatModel, ChatReasoningEffort, Skill } from "@plot/api-client";
import { plotApiClient } from "@/lib/api-client";
import PromptBar, { type PromptModelOption } from "@/components/primitives/prompt-bar";
import {
  CHAT_MODELS,
  CHAT_MODEL_STORAGE_KEY,
  CHAT_REASONING_EFFORT_STORAGE_KEY,
  parseChatModel,
  parseChatReasoningEffort,
} from "./chat-models";
import { resolveComposerReferenceIds } from "./chat-workspace-utils";

type ChatComposerProps = {
  onSubmit: (
    message: string,
    referenceIds: string[],
    skillIds: string[],
    model: ChatModel,
    reasoningEffort: ChatReasoningEffort,
  ) => void;
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
  const [chatModels, setChatModels] = useState<readonly PromptModelOption[]>(CHAT_MODELS);
  const [model, setModel] = useState<ChatModel>("auto");
  const [reasoningEffort, setReasoningEffort] = useState<ChatReasoningEffort>("medium");
  const [modelPreferenceLoaded, setModelPreferenceLoaded] = useState(false);
  const [reasoningPreferenceLoaded, setReasoningPreferenceLoaded] = useState(false);
  const modelChangedRef = useRef(false);
  const reasoningChangedRef = useRef(false);
  const isSendDisabled = busy || !canGenerate;

  useEffect(() => {
    if (typeof plotApiClient.listChatModelCapabilities !== "function") return;
    const controller = new AbortController();
    plotApiClient
      .listChatModelCapabilities({ signal: controller.signal })
      .then((capabilities) => {
        if (controller.signal.aborted) return;
        const autoCapability = capabilities.find((capability) => capability.model === "auto");
        if (!autoCapability) return;
        setChatModels(CHAT_MODELS.map((item) => item.id === "auto"
          ? {
              ...item,
              reasoningEfforts: autoCapability.reasoningEfforts,
              reasoningDefault: autoCapability.reasoningDefault ?? undefined,
            }
          : item));
      })
      .catch(() => {});
    return () => controller.abort();
  }, []);

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
        if (!reasoningChangedRef.current) {
          setReasoningEffort(
            parseChatReasoningEffort(window.localStorage.getItem(CHAT_REASONING_EFFORT_STORAGE_KEY)) ?? "medium",
          );
        }
      } catch {
        if (!modelChangedRef.current) setModel("auto");
        if (!reasoningChangedRef.current) setReasoningEffort("medium");
      } finally {
        setModelPreferenceLoaded(true);
        setReasoningPreferenceLoaded(true);
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

  useEffect(() => {
    if (!reasoningPreferenceLoaded) return;
    try {
      window.localStorage.setItem(CHAT_REASONING_EFFORT_STORAGE_KEY, reasoningEffort);
    } catch {
      // Storage can be unavailable in private browsing; the in-memory selection still works.
    }
  }, [reasoningEffort, reasoningPreferenceLoaded]);

  function handleSend(text: string) {
    if (submittingRef.current || isSendDisabled) return;
    const trimmed = text.trim();
    if (!trimmed) return;

    const selectedModel = chatModels.find((item) => item.id === model);
    const supportedReasoningEfforts = selectedModel?.reasoningEfforts;
    const effectiveReasoningEffort = supportedReasoningEfforts && supportedReasoningEfforts.length > 0 &&
      !supportedReasoningEfforts.includes(reasoningEffort)
      ? selectedModel.reasoningDefault ?? supportedReasoningEfforts[0] ?? reasoningEffort
      : reasoningEffort;
    if (effectiveReasoningEffort !== reasoningEffort) {
      reasoningChangedRef.current = true;
      setReasoningEffort(effectiveReasoningEffort);
    }

    submittingRef.current = true;
    onSubmit(trimmed, resolveComposerReferenceIds(references, []), skillIds, model, effectiveReasoningEffort);
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
            models={chatModels}
            selectedModelId={model}
            onSelectedModelIdChange={(value) => {
              modelChangedRef.current = true;
              setModel(parseChatModel(value) ?? "auto");
            }}
            reasoningEffort={reasoningEffort}
            onReasoningEffortChange={(value) => {
              reasoningChangedRef.current = true;
              setReasoningEffort(value);
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
      className="w-full bg-white px-4 pb-4 pt-3 dark:bg-[#111113] sm:px-6"
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
          models={chatModels}
          selectedModelId={model}
          onSelectedModelIdChange={(value) => {
            modelChangedRef.current = true;
            setModel(parseChatModel(value) ?? "auto");
          }}
          reasoningEffort={reasoningEffort}
          onReasoningEffortChange={(value) => {
            reasoningChangedRef.current = true;
            setReasoningEffort(value);
          }}
          onSend={handleSend}
        />
      </div>
    </div>
  );
}

export { PromptBar };
export default PromptBar;
