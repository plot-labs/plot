"use client";

import { useRef, type ClipboardEvent, type KeyboardEvent } from "react";

const CODE_LENGTH = 6;

type VerificationCodeInputProps = {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  disabled?: boolean;
  error?: boolean;
  describedBy?: string;
};

export function VerificationCodeInput({
  id,
  label,
  value,
  onChange,
  disabled = false,
  error = false,
  describedBy,
}: VerificationCodeInputProps) {
  const inputRefs = useRef<Array<HTMLInputElement | null>>([]);
  const digits = value.padEnd(CODE_LENGTH, " ").split("");

  function focusDigit(index: number) {
    inputRefs.current[Math.max(0, Math.min(index, CODE_LENGTH - 1))]?.focus();
  }

  function updateDigit(index: number, nextDigit: string) {
    const nextValue = digits.map((digit, digitIndex) => digitIndex === index ? nextDigit : digit.trim()).join("");
    onChange(nextValue.slice(0, CODE_LENGTH));
    if (nextDigit && index < CODE_LENGTH - 1) focusDigit(index + 1);
  }

  function handleChange(index: number, rawValue: string) {
    const nextDigits = rawValue.replace(/\D/g, "");
    if (nextDigits.length <= 1) {
      updateDigit(index, nextDigits);
      return;
    }

    const nextValue = digits.map((digit) => digit.trim());
    nextDigits.slice(0, CODE_LENGTH - index).split("").forEach((digit, offset) => {
      nextValue[index + offset] = digit;
    });
    onChange(nextValue.join(""));
    focusDigit(Math.min(index + nextDigits.length, CODE_LENGTH) - 1);
  }

  function handleKeyDown(index: number, event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === "Backspace" && !digits[index].trim() && index > 0) {
      event.preventDefault();
      updateDigit(index - 1, "");
      focusDigit(index - 1);
    } else if (event.key === "ArrowLeft" && index > 0) {
      event.preventDefault();
      focusDigit(index - 1);
    } else if (event.key === "ArrowRight" && index < CODE_LENGTH - 1) {
      event.preventDefault();
      focusDigit(index + 1);
    }
  }

  function handlePaste(event: ClipboardEvent<HTMLInputElement>) {
    event.preventDefault();
    const pastedCode = event.clipboardData.getData("text").replace(/\D/g, "").slice(0, CODE_LENGTH);
    if (!pastedCode) return;
    onChange(pastedCode);
    focusDigit(Math.min(pastedCode.length, CODE_LENGTH) - 1);
  }

  return (
    <div className="grid gap-4" aria-describedby={describedBy}>
      <span id={`${id}-label`} className="text-sm font-medium leading-none text-black/75">{label}</span>
      <div className="flex justify-center gap-2" role="group" aria-labelledby={`${id}-label`}>
        {Array.from({ length: CODE_LENGTH }, (_, index) => (
          <input
            key={`${id}-${index}`}
            ref={(element) => {
              inputRefs.current[index] = element;
            }}
            id={`${id}-${index + 1}`}
            name={`${id}-${index + 1}`}
            type="text"
            inputMode="numeric"
            autoComplete={index === 0 ? "one-time-code" : "off"}
            pattern="[0-9]*"
            value={digits[index].trim()}
            onChange={(event) => handleChange(index, event.target.value)}
            onKeyDown={(event) => handleKeyDown(index, event)}
            onPaste={handlePaste}
            onFocus={(event) => event.currentTarget.select()}
            maxLength={1}
            disabled={disabled}
            required
            aria-label={`${label}, digit ${index + 1} of ${CODE_LENGTH}`}
            aria-invalid={error}
            className="size-12 min-w-0 rounded-xl border border-black/15 bg-white/55 text-center text-xl font-medium text-black shadow-[inset_0_1px_0_rgb(255_255_255/0.95),0_8px_20px_rgb(0_0_0/0.04)] backdrop-blur-xl transition-[border-color,box-shadow,background-color,transform] placeholder:text-black/20 focus:scale-[1.02] focus:border-black/25 focus:bg-white/75 focus:shadow-[inset_0_1px_0_rgb(255_255_255),0_10px_24px_rgb(0_0_0/0.08),0_0_0_3px_rgb(255_255_255/0.45)] focus:outline-none aria-invalid:border-red-500/40 aria-invalid:bg-red-50/55 aria-invalid:ring-2 aria-invalid:ring-red-500/10 disabled:pointer-events-none disabled:opacity-50 sm:size-[52px]"
          />
        ))}
      </div>
    </div>
  );
}
