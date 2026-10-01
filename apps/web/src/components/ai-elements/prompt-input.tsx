"use client";
// AI Elements prompt-input registry snapshot 2026-10-01 (660efa7c9a10).
// Text-only subset: no file, screenshot, voice, provider or automatic reset.
// The owner clears its controlled draft only after an accepted submission.
import {useState, type ComponentProps, type FormEvent} from "react";

export type PromptInputMessage = {text: string};
export type PromptInputProps = Omit<ComponentProps<"form">, "onSubmit"> & {
  onSubmit: (message: PromptInputMessage, event: FormEvent<HTMLFormElement>) => void;
};
export function PromptInput({onSubmit, ...props}: PromptInputProps) {
  return <form {...props} onSubmit={event => {
    event.preventDefault();
    onSubmit({text: String(new FormData(event.currentTarget).get("message") ?? "")}, event);
  }} />;
}
export function PromptInputTextarea({onKeyDown, onCompositionStart, onCompositionEnd, ...props}: ComponentProps<"textarea">) {
  const [isComposing, setIsComposing] = useState(false);
  return <textarea name="message" {...props}
    onCompositionStart={event=>{setIsComposing(true);onCompositionStart?.(event);}}
    onCompositionEnd={event=>{setIsComposing(false);onCompositionEnd?.(event);}}
    onKeyDown={event=>{
      if (isComposing || event.nativeEvent.isComposing || event.keyCode === 229) return;
      onKeyDown?.(event);
      if (event.defaultPrevented || event.key !== "Enter" || event.shiftKey) return;
      event.preventDefault();
      const button=event.currentTarget.form?.querySelector<HTMLButtonElement>('button[type="submit"]');
      if (button && !button.disabled) event.currentTarget.form?.requestSubmit();
    }}
  />;
}
export const PromptInputSubmit = (props: ComponentProps<"button">) => <button type="submit" {...props}/>;
