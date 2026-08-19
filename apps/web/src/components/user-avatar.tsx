import { Blobatar } from "blobatar/react";

import { cn } from "@/lib/utils";

type UserAvatarProps = {
  userId: string;
  size: number;
  className?: string;
};

export function UserAvatar({ userId, size, className }: UserAvatarProps) {
  return (
    <span
      className={cn(
        "inline-flex shrink-0 overflow-hidden rounded-full border border-black/10 bg-white dark:border-white/10 dark:bg-white/10",
        className,
      )}
      style={{ width: size, height: size }}
      aria-hidden="true"
    >
      <Blobatar name={userId} size={size} background="circle" alt="" />
    </span>
  );
}
