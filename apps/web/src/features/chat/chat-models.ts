import type { ChatModel, ChatReasoningEffort } from "@plot/api-client";
import type { PromptModelOption } from "@/components/primitives/prompt-bar";

export const CHAT_MODEL_STORAGE_KEY = "plot.chat.model";
export const CHAT_REASONING_EFFORT_STORAGE_KEY = "plot.chat.reasoning-effort";

const CHAT_MODEL_REASONING_EFFORTS = {
  auto: ["none", "minimal", "low", "medium", "high", "xhigh", "max"],
  "anthropic/claude-opus-5": ["low", "medium", "high", "xhigh", "max"],
  "anthropic/claude-opus-4.8": ["low", "medium", "high", "xhigh", "max"],
  "anthropic/claude-sonnet-5": ["low", "medium", "high", "xhigh", "max"],
  "anthropic/claude-sonnet-4.6": ["low", "medium", "high", "max"],
  "anthropic/claude-haiku-4.5": [],
  "openai/gpt-5.4": ["none", "low", "medium", "high", "xhigh"],
  "openai/gpt-5.5": ["none", "low", "medium", "high", "xhigh"],
  "openai/gpt-5.6-sol": ["none", "low", "medium", "high", "xhigh", "max"],
  "openai/gpt-5.6-luna": ["none", "low", "medium", "high", "xhigh", "max"],
  "google/gemini-3.8-flash": ["low", "medium", "high"],
  "deepseek/deepseek-v4.1-flash": ["low", "high", "max"],
  "x-ai/grok-4.6": ["low", "medium", "high", "xhigh"],
  "qwen/qwen3.8-max-0902": ["minimal", "low", "medium", "high", "xhigh"],
} as const satisfies Record<ChatModel, readonly ChatReasoningEffort[]>;

export const CHAT_MODELS = [
  {
    id: "auto",
    label: "Auto",
    description: "Picks the workspace default model",
    pricing: "Varies by selected model",
    provider: "auto",
    reasoningEfforts: CHAT_MODEL_REASONING_EFFORTS.auto,
    reasoningDefault: "medium",
  },
  {
    id: "anthropic/claude-opus-5",
    label: "Claude Opus 5",
    description: "Most advanced reasoning",
    pricing: "$5 input / $25 output per 1M",
    provider: "anthropic",
    reasoningEfforts: CHAT_MODEL_REASONING_EFFORTS["anthropic/claude-opus-5"],
    reasoningDefault: "high",
  },
  {
    id: "anthropic/claude-opus-4.8",
    label: "Claude Opus 4.8",
    description: "Deepest reasoning",
    pricing: "$5 input / $25 output per 1M",
    provider: "anthropic",
    reasoningEfforts: CHAT_MODEL_REASONING_EFFORTS["anthropic/claude-opus-4.8"],
    reasoningDefault: "high",
  },
  {
    id: "anthropic/claude-sonnet-5",
    label: "Claude Sonnet 5",
    description: "Near-Opus quality at Sonnet speed",
    pricing: "$2 input / $10 output per 1M",
    provider: "anthropic",
    reasoningEfforts: CHAT_MODEL_REASONING_EFFORTS["anthropic/claude-sonnet-5"],
    reasoningDefault: "high",
  },
  {
    id: "anthropic/claude-sonnet-4.6",
    label: "Claude Sonnet 4.6",
    description: "Best everyday default",
    pricing: "$3 input / $15 output per 1M",
    provider: "anthropic",
    reasoningEfforts: CHAT_MODEL_REASONING_EFFORTS["anthropic/claude-sonnet-4.6"],
    reasoningDefault: "medium",
  },
  {
    id: "anthropic/claude-haiku-4.5",
    label: "Claude Haiku 4.5",
    description: "Fastest responses",
    pricing: "$1 input / $5 output per 1M",
    provider: "anthropic",
    reasoningEfforts: CHAT_MODEL_REASONING_EFFORTS["anthropic/claude-haiku-4.5"],
  },
  {
    id: "openai/gpt-5.4",
    label: "GPT-5.4",
    description: "Best for creative writing",
    pricing: "$2.50 input / $15 output per 1M",
    provider: "openai",
    reasoningEfforts: CHAT_MODEL_REASONING_EFFORTS["openai/gpt-5.4"],
    reasoningDefault: "medium",
  },
  {
    id: "openai/gpt-5.5",
    label: "GPT-5.5",
    description: "Latest OpenAI flagship",
    pricing: "$5 input / $30 output per 1M",
    provider: "openai",
    reasoningEfforts: CHAT_MODEL_REASONING_EFFORTS["openai/gpt-5.5"],
    reasoningDefault: "medium",
  },
  {
    id: "openai/gpt-5.6-sol",
    label: "GPT-5.6 Sol",
    description: "Balanced reasoning and speed",
    pricing: "$2 input / $10 output per 1M",
    provider: "openai",
    reasoningEfforts: CHAT_MODEL_REASONING_EFFORTS["openai/gpt-5.6-sol"],
    reasoningDefault: "medium",
  },
  {
    id: "openai/gpt-5.6-luna",
    label: "GPT-5.6 Luna",
    description: "Fast, cost-efficient everyday model",
    pricing: "$0.20 input / $1.20 output per 1M",
    provider: "openai",
    reasoningEfforts: CHAT_MODEL_REASONING_EFFORTS["openai/gpt-5.6-luna"],
    reasoningDefault: "medium",
  },
  {
    id: "google/gemini-3.8-flash",
    label: "Gemini 3.8 Flash",
    description: "Fast multimodal long-context model",
    pricing: "$0.75 input / $3.75 output per 1M",
    provider: "google",
    reasoningEfforts: CHAT_MODEL_REASONING_EFFORTS["google/gemini-3.8-flash"],
    reasoningDefault: "medium",
  },
  {
    id: "deepseek/deepseek-v4.1-flash",
    label: "DeepSeek V4.1 Flash",
    description: "Low-cost reasoning and coding",
    pricing: "$0.30 input / $1.20 output per 1M",
    provider: "deepseek",
    reasoningEfforts: CHAT_MODEL_REASONING_EFFORTS["deepseek/deepseek-v4.1-flash"],
    reasoningDefault: "high",
  },
  {
    id: "x-ai/grok-4.6",
    label: "Grok 4.6",
    description: "Strong reasoning with a 500K context",
    pricing: "$2 input / $6 output per 1M",
    provider: "xai",
    reasoningEfforts: CHAT_MODEL_REASONING_EFFORTS["x-ai/grok-4.6"],
    reasoningDefault: "high",
  },
  {
    id: "qwen/qwen3.8-max-0902",
    label: "Qwen3.8 Max",
    description: "Strong multilingual long-context model",
    pricing: "$2 input / $6 output per 1M",
    provider: "qwen",
    reasoningEfforts: CHAT_MODEL_REASONING_EFFORTS["qwen/qwen3.8-max-0902"],
    reasoningDefault: "xhigh",
  },
] as const satisfies readonly PromptModelOption[];

const chatModelIds = new Set<string>(CHAT_MODELS.map((model) => model.id));
const chatReasoningEffortIds = new Set<ChatReasoningEffort>([
  "none",
  "minimal",
  "low",
  "medium",
  "high",
  "xhigh",
  "max",
]);

export function parseChatModel(value: string | null): ChatModel | null {
  return value && chatModelIds.has(value) ? (value as ChatModel) : null;
}

export function parseChatReasoningEffort(value: string | null): ChatReasoningEffort | null {
  return value && chatReasoningEffortIds.has(value as ChatReasoningEffort)
    ? (value as ChatReasoningEffort)
    : null;
}
