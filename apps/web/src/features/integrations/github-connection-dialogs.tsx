"use client";

import { GithubIcon, Search01Icon } from "@hugeicons/core-free-icons";
import { HugeiconsIcon } from "@hugeicons/react";
import { ArrowUpRight, Check, ChevronDown, LoaderCircle, LockKeyhole, Plus, X } from "lucide-react";
import Image from "next/image";
import { useEffect, useId, useRef, useState, type ReactNode } from "react";

import type { GitHubAvailableInstallation, GitHubRepository } from "@/lib/api-client";
import { trapTabKey } from "@/lib/focus-trap";

export function GitHubAccountAvatar({ login, label, size = 36 }: { login: string | null; label: string; size?: number }) {
  const [failedLogin, setFailedLogin] = useState<string | null>(null);
  const showImage = login !== null && failedLogin !== login;

  return (
    <span
      aria-hidden="true"
      style={{ width: size, height: size }}
      className="flex shrink-0 items-center justify-center overflow-hidden rounded-full bg-black/[0.06] text-[13px] font-semibold text-black/62 dark:bg-white/[0.09] dark:text-white/68"
    >
      {showImage ? (
        <Image
          src={`https://github.com/${encodeURIComponent(login)}.png?size=${size * 2}`}
          alt=""
          width={size}
          height={size}
          unoptimized
          className="size-full object-cover"
          onError={() => setFailedLogin(login)}
        />
      ) : label.charAt(0).toUpperCase()}
    </span>
  );
}

export const githubAppPermissions = [
  "Repository metadata",
  "Contents",
  "Pull requests",
  "Releases and tags",
] as const;

function IntegrationDialog({
  open,
  title,
  description,
  busy = false,
  onClose,
  children,
  footer,
}: {
  open: boolean;
  title: string;
  description: string;
  busy?: boolean;
  onClose: () => void;
  children: ReactNode;
  footer: ReactNode;
}) {
  const dialogRef = useRef<HTMLDivElement>(null);
  const titleId = useId();
  const descriptionId = useId();
  const closeRef = useRef(onClose);
  const busyRef = useRef(busy);

  useEffect(() => {
    closeRef.current = onClose;
    busyRef.current = busy;
  });

  useEffect(() => {
    if (!open) return;
    const dialog = dialogRef.current;
    const trigger = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    dialog?.focus();

    function onKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        event.preventDefault();
        if (!busyRef.current) closeRef.current();
        return;
      }
      if (dialog) trapTabKey(event, dialog);
    }

    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("keydown", onKeyDown);
      trigger?.focus();
    };
  }, [open]);

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <button
        type="button"
        aria-label="Close dialog"
        tabIndex={-1}
        onClick={() => { if (!busy) onClose(); }}
        className="absolute inset-0 bg-black/[0.18] dark:bg-black/40"
      />
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={descriptionId}
        tabIndex={-1}
        className="glass-layer relative flex max-h-[85svh] w-full max-w-[480px] flex-col overflow-hidden rounded-[12px] border border-black/10 outline-none dark:border-white/12"
      >
        <header className="flex shrink-0 items-start gap-3 px-5 pt-5">
          <div className="flex size-10 shrink-0 items-center justify-center rounded-[9px] border border-black/[0.08] bg-white dark:border-white/10">
            <HugeiconsIcon icon={GithubIcon} size={22} color="#181717" strokeWidth={1.5} aria-hidden="true" />
          </div>
          <div className="min-w-0 flex-1">
            <h2 id={titleId} className="text-[16px] font-semibold tracking-[-0.01em] text-black/86 dark:text-white/90">{title}</h2>
            <p id={descriptionId} className="mt-1 text-[13px] leading-5 text-black/50 dark:text-white/52">{description}</p>
          </div>
          <button
            type="button"
            aria-label="Close"
            onClick={onClose}
            disabled={busy}
            className="glass-button glass-icon inline-flex size-8 shrink-0 items-center justify-center"
          >
            <X aria-hidden="true" className="size-4" />
          </button>
        </header>
        <div className="min-h-0 flex-1 overflow-y-auto px-5 py-4">{children}</div>
        <footer className="flex shrink-0 items-center justify-end gap-2 border-t border-black/[0.07] px-5 py-3.5 dark:border-white/[0.08]">
          {footer}
        </footer>
      </div>
    </div>
  );
}

export function ConnectGitHubDialog({
  open,
  connecting,
  onClose,
  onConnect,
}: {
  open: boolean;
  connecting: boolean;
  onClose: () => void;
  onConnect: () => void;
}) {
  return (
    <IntegrationDialog
      open={open}
      title="Connect GitHub"
      description="Install the Plot GitHub App so Plot can follow releases, merged pull requests, and commits."
      busy={connecting}
      onClose={onClose}
      footer={(
        <>
          <button type="button" onClick={onClose} disabled={connecting} className="glass-button">Cancel</button>
          <button
            type="button"
            onClick={onConnect}
            disabled={connecting}
            aria-busy={connecting}
            className="glass-button glass-primary inline-flex items-center gap-1.5 disabled:cursor-wait"
          >
            {connecting
              ? <LoaderCircle className="size-3.5 animate-spin" aria-hidden="true" />
              : <HugeiconsIcon icon={GithubIcon} size={15} color="currentColor" strokeWidth={1.6} aria-hidden="true" />}
            {connecting ? "Redirecting…" : "Install on GitHub"}
          </button>
        </>
      )}
    >
      <p className="text-[13px] leading-5 text-black/62 dark:text-white/62">
        You will choose an account or organization on GitHub and the repositories Plot can read. You can change this access on GitHub at any time.
      </p>
      <h3 className="mt-4 text-[12px] font-semibold text-black/70 dark:text-white/72">Read-only permissions</h3>
      <ul className="mt-2 grid grid-cols-2 gap-x-5">
        {githubAppPermissions.map((permission) => (
          <li key={permission} className="border-b border-black/[0.07] py-2 text-[12px] text-black/55 dark:border-white/[0.08] dark:text-white/52">
            {permission}
          </li>
        ))}
      </ul>
    </IntegrationDialog>
  );
}

export type GitHubAccountOption = { id: string; login: string | null; label: string };

function GitHubAccountSelect({
  accounts,
  selectedId,
  disabled,
  onSelect,
  onAddAccount,
}: {
  accounts: GitHubAccountOption[];
  selectedId: string;
  disabled: boolean;
  onSelect: (id: string) => void;
  onAddAccount: () => void;
}) {
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  const selected = accounts.find((account) => account.id === selectedId) ?? accounts[0];

  useEffect(() => {
    if (!open) return;
    function dismissIfOutside(event: Event) {
      if (event.target instanceof Node && !rootRef.current?.contains(event.target)) setOpen(false);
    }
    document.addEventListener("pointerdown", dismissIfOutside, true);
    return () => document.removeEventListener("pointerdown", dismissIfOutside, true);
  }, [open]);

  return (
    <div
      ref={rootRef}
      className="relative"
      onKeyDown={(event) => {
        // Close only the menu; the surrounding dialog also listens for Escape.
        if (event.key === "Escape" && open) {
          event.stopPropagation();
          setOpen(false);
        }
      }}
    >
      <button
        type="button"
        aria-label="GitHub account"
        aria-haspopup="menu"
        aria-expanded={open}
        disabled={disabled}
        onClick={() => setOpen((value) => !value)}
        className="flex h-9 w-full items-center justify-between gap-2 rounded-[8px] border border-black/10 bg-white px-2.5 text-[13px] text-black/78 transition hover:border-black/20 disabled:opacity-50 dark:border-white/12 dark:bg-white/[0.04] dark:text-white/82"
      >
        <span className="flex min-w-0 items-center gap-2">
          {selected && <GitHubAccountAvatar login={selected.login} label={selected.label} size={20} />}
          <span className="truncate">{selected?.label ?? "Select account"}</span>
        </span>
        <ChevronDown className="size-4 shrink-0 text-black/40 dark:text-white/42" aria-hidden="true" />
      </button>
      {open && (
        <div role="menu" aria-label="GitHub accounts" className="glass-layer absolute inset-x-0 top-full z-10 mt-1 rounded-[9px] border border-black/10 p-1 shadow-lg dark:border-white/12">
          {accounts.map((account) => (
            <button
              key={account.id}
              type="button"
              role="menuitemradio"
              aria-checked={account.id === selected?.id}
              onClick={() => {
                setOpen(false);
                onSelect(account.id);
              }}
              className="flex w-full items-center gap-2 rounded-[6px] px-2 py-1.5 text-left text-[13px] text-black/78 hover:bg-black/[0.04] dark:text-white/82 dark:hover:bg-white/[0.06]"
            >
              <GitHubAccountAvatar login={account.login} label={account.label} size={20} />
              <span className="min-w-0 flex-1 truncate">{account.label}</span>
              {account.id === selected?.id && <Check className="size-4 shrink-0" aria-hidden="true" />}
            </button>
          ))}
          <div className="my-1 border-t border-black/[0.07] dark:border-white/[0.08]" />
          <button
            type="button"
            role="menuitem"
            onClick={() => {
              setOpen(false);
              onAddAccount();
            }}
            className="flex w-full items-center gap-2 rounded-[6px] px-2 py-1.5 text-left text-[13px] text-black/78 hover:bg-black/[0.04] dark:text-white/82 dark:hover:bg-white/[0.06]"
          >
            <Plus className="size-4 shrink-0" aria-hidden="true" />
            Add GitHub account
          </button>
        </div>
      )}
    </div>
  );
}

export function SelectRepositoriesDialog({
  open,
  accounts,
  accountId,
  loadRepositories,
  saving,
  onClose,
  onSave,
  onSelectAccount,
  onAddAccount,
  onManageAccess,
}: {
  open: boolean;
  accounts: GitHubAccountOption[];
  accountId: string;
  loadRepositories: (signal: AbortSignal) => Promise<GitHubRepository[]>;
  saving: boolean;
  onClose: () => void;
  onSave: (repositories: GitHubRepository[]) => void;
  onSelectAccount: (id: string) => void;
  onAddAccount: () => void;
  onManageAccess: () => void;
}) {
  return open ? (
    <SelectRepositoriesDialogContent
      accounts={accounts}
      accountId={accountId}
      loadRepositories={loadRepositories}
      saving={saving}
      onClose={onClose}
      onSave={onSave}
      onSelectAccount={onSelectAccount}
      onAddAccount={onAddAccount}
      onManageAccess={onManageAccess}
    />
  ) : null;
}

function SelectRepositoriesDialogContent({
  accounts,
  accountId,
  loadRepositories,
  saving,
  onClose,
  onSave,
  onSelectAccount,
  onAddAccount,
  onManageAccess,
}: Omit<Parameters<typeof SelectRepositoriesDialog>[0], "open">) {
  const [repositories, setRepositories] = useState<GitHubRepository[]>([]);
  const [loading, setLoading] = useState(true);
  const [failed, setFailed] = useState(false);
  const [selected, setSelected] = useState<number[]>([]);
  const [query, setQuery] = useState("");
  const [reloadNonce, setReloadNonce] = useState(0);
  const [loadedAccountId, setLoadedAccountId] = useState(accountId);
  const loadRef = useRef(loadRepositories);

  // Switching accounts starts a fresh selection for that account's repositories.
  if (loadedAccountId !== accountId) {
    setLoadedAccountId(accountId);
    setRepositories([]);
    setSelected([]);
    setQuery("");
    setFailed(false);
    setLoading(true);
  }

  useEffect(() => {
    loadRef.current = loadRepositories;
  });

  useEffect(() => {
    const controller = new AbortController();
    loadRef.current(controller.signal)
      .then((next) => {
        if (controller.signal.aborted) return;
        setRepositories(next);
        setFailed(false);
      })
      .catch(() => {
        if (!controller.signal.aborted) setFailed(true);
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [reloadNonce, accountId]);

  const normalizedQuery = query.trim().toLowerCase();
  const visibleRepositories = repositories.filter((repository) => repository.displayName.toLowerCase().includes(normalizedQuery));
  const selectedRepositories = repositories.filter((repository) => selected.includes(repository.externalRepositoryId));
  const saveLabel = selected.length === 0
    ? "Add repositories"
    : `Add ${selected.length} ${selected.length === 1 ? "repository" : "repositories"}`;

  const toggle = (externalRepositoryId: number) => {
    setSelected((current) => current.includes(externalRepositoryId)
      ? current.filter((id) => id !== externalRepositoryId)
      : [...current, externalRepositoryId]);
  };

  return (
    <IntegrationDialog
      open
      title="Add repositories"
      description="Choose which repositories Plot should follow. Only repositories granted to the Plot GitHub App appear here."
      busy={saving}
      onClose={onClose}
      footer={(
        <>
          <button type="button" onClick={onClose} disabled={saving} className="glass-button">Cancel</button>
          <button
            type="button"
            onClick={() => onSave(selectedRepositories)}
            disabled={saving || selected.length === 0}
            aria-busy={saving}
            className="glass-button glass-primary inline-flex items-center gap-1.5 disabled:cursor-wait"
          >
            {saving && <LoaderCircle className="size-3.5 animate-spin" aria-hidden="true" />}
            {saving ? "Adding…" : saveLabel}
          </button>
        </>
      )}
    >
      <GitHubAccountSelect
        accounts={accounts}
        selectedId={accountId}
        disabled={saving}
        onSelect={onSelectAccount}
        onAddAccount={onAddAccount}
      />
      <label className="mt-2 flex h-9 items-center gap-2 rounded-[8px] border border-black/10 bg-white px-2.5 text-black/40 focus-within:border-black/20 focus-within:ring-2 focus-within:ring-black/[0.04] dark:border-white/12 dark:bg-white/[0.04] dark:text-white/42">
        <HugeiconsIcon icon={Search01Icon} size={15} color="currentColor" strokeWidth={1.5} aria-hidden="true" />
        <span className="sr-only">Search repositories</span>
        <input
          type="search"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder="Search repositories"
          className="min-w-0 flex-1 bg-transparent text-[13px] text-black/75 outline-none placeholder:text-black/35 dark:text-white/80 dark:placeholder:text-white/35"
        />
      </label>

      <div className="mt-3 min-h-[120px]">
        {loading && (
          <div className="flex items-center gap-2 py-6 text-[13px] text-black/50 dark:text-white/50">
            <LoaderCircle className="size-4 animate-spin" aria-hidden="true" />
            Loading repositories…
          </div>
        )}
        {!loading && failed && (
          <div role="alert" className="rounded-[9px] border border-amber-300 bg-amber-50 p-3 text-[13px] text-amber-900 dark:border-amber-400/30 dark:bg-amber-500/10 dark:text-amber-100">
            Repositories could not be loaded from GitHub.{" "}
            <button
              type="button"
              onClick={() => {
                setLoading(true);
                setReloadNonce((value) => value + 1);
              }}
              className="font-medium underline underline-offset-2"
            >
              Try again
            </button>
          </div>
        )}
        {!loading && !failed && repositories.length === 0 && (
          <p className="rounded-[9px] border border-dashed border-black/[0.12] px-3 py-6 text-center text-[13px] text-black/50 dark:border-white/[0.14] dark:text-white/50">
            No repositories are granted to the Plot GitHub App yet.
          </p>
        )}
        {!loading && !failed && repositories.length > 0 && visibleRepositories.length === 0 && (
          <p className="py-6 text-center text-[13px] text-black/45 dark:text-white/45">No repositories match “{query.trim()}”.</p>
        )}
        {!loading && !failed && visibleRepositories.length > 0 && (
          <ul className="divide-y divide-black/[0.07] rounded-[9px] border border-black/10 dark:divide-white/[0.08] dark:border-white/12" aria-label="Granted repositories">
            {visibleRepositories.map((repository) => {
              const connected = repository.status === "ACTIVE";
              return (
                <li key={repository.externalRepositoryId}>
                  <label className={`flex items-center gap-3 px-3 py-2.5 ${connected ? "cursor-default" : "cursor-pointer hover:bg-black/[0.02] dark:hover:bg-white/[0.03]"}`}>
                    <input
                      type="checkbox"
                      checked={connected || selected.includes(repository.externalRepositoryId)}
                      disabled={connected || saving}
                      onChange={() => toggle(repository.externalRepositoryId)}
                      className="size-4 shrink-0 accent-black dark:accent-white"
                    />
                    <span className="flex min-w-0 flex-1 items-center gap-1.5">
                      <span className="truncate text-[13px] font-medium text-black/78 dark:text-white/82">{repository.displayName}</span>
                      {repository.visibility === "PRIVATE" && (
                        <>
                          <LockKeyhole className="size-3.5 shrink-0 text-black/38 dark:text-white/40" aria-hidden="true" />
                          <span className="sr-only">Private repository</span>
                        </>
                      )}
                    </span>
                    {connected && <span className="shrink-0 text-[11px] text-black/40 dark:text-white/42">Added</span>}
                  </label>
                </li>
              );
            })}
          </ul>
        )}
      </div>

      <button
        type="button"
        onClick={onManageAccess}
        disabled={saving}
        className="mt-3 inline-flex items-center gap-1 text-[12px] text-black/45 underline-offset-4 hover:text-black/70 hover:underline dark:text-white/45 dark:hover:text-white/70"
      >
        Missing a repository? Update access on GitHub
        <ArrowUpRight className="size-3" aria-hidden="true" />
      </button>
    </IntegrationDialog>
  );
}

export function AddGitHubAccountDialog({
  open,
  loadInstallations,
  connectingInstallationId,
  error,
  installing,
  onClose,
  onConnect,
  onInstallElsewhere,
}: {
  open: boolean;
  loadInstallations: (signal: AbortSignal) => Promise<GitHubAvailableInstallation[]>;
  connectingInstallationId: number | null;
  error: string | null;
  installing: boolean;
  onClose: () => void;
  onConnect: (installation: GitHubAvailableInstallation) => void;
  onInstallElsewhere: () => void;
}) {
  return open ? (
    <AddGitHubAccountDialogContent
      loadInstallations={loadInstallations}
      connectingInstallationId={connectingInstallationId}
      error={error}
      installing={installing}
      onClose={onClose}
      onConnect={onConnect}
      onInstallElsewhere={onInstallElsewhere}
    />
  ) : null;
}

function AddGitHubAccountDialogContent({
  loadInstallations,
  connectingInstallationId,
  error,
  installing,
  onClose,
  onConnect,
  onInstallElsewhere,
}: Omit<Parameters<typeof AddGitHubAccountDialog>[0], "open">) {
  const [installations, setInstallations] = useState<GitHubAvailableInstallation[]>([]);
  const [loading, setLoading] = useState(true);
  const [failed, setFailed] = useState(false);
  const [reloadNonce, setReloadNonce] = useState(0);
  const loadRef = useRef(loadInstallations);
  const busy = connectingInstallationId !== null || installing;

  useEffect(() => {
    loadRef.current = loadInstallations;
  });

  useEffect(() => {
    const controller = new AbortController();
    loadRef.current(controller.signal)
      .then((next) => {
        if (controller.signal.aborted) return;
        setInstallations(next);
        setFailed(false);
      })
      .catch(() => {
        if (!controller.signal.aborted) setFailed(true);
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [reloadNonce]);

  return (
    <IntegrationDialog
      open
      title="Add GitHub account"
      description="Connect another account or organization where the Plot GitHub App is installed."
      busy={busy}
      onClose={onClose}
      footer={(
        <>
          <button type="button" onClick={onClose} disabled={busy} className="glass-button">Cancel</button>
          <button
            type="button"
            onClick={onInstallElsewhere}
            disabled={busy}
            aria-busy={installing}
            className="glass-button glass-primary inline-flex items-center gap-1.5 disabled:cursor-wait"
          >
            {installing
              ? <LoaderCircle className="size-3.5 animate-spin" aria-hidden="true" />
              : <HugeiconsIcon icon={GithubIcon} size={15} color="currentColor" strokeWidth={1.6} aria-hidden="true" />}
            {installing ? "Redirecting…" : "Install on another account"}
          </button>
        </>
      )}
    >
      {error && (
        <p role="alert" className="mb-3 rounded-[9px] border border-amber-300 bg-amber-50 p-3 text-[13px] text-amber-900 dark:border-amber-400/30 dark:bg-amber-500/10 dark:text-amber-100">
          {error}
        </p>
      )}
      {loading && (
        <div className="flex items-center gap-2 py-6 text-[13px] text-black/50 dark:text-white/50">
          <LoaderCircle className="size-4 animate-spin" aria-hidden="true" />
          Loading GitHub accounts…
        </div>
      )}
      {!loading && failed && (
        <div role="alert" className="rounded-[9px] border border-amber-300 bg-amber-50 p-3 text-[13px] text-amber-900 dark:border-amber-400/30 dark:bg-amber-500/10 dark:text-amber-100">
          GitHub accounts could not be loaded.{" "}
          <button
            type="button"
            onClick={() => {
              setLoading(true);
              setReloadNonce((value) => value + 1);
            }}
            className="font-medium underline underline-offset-2"
          >
            Try again
          </button>
        </div>
      )}
      {!loading && !failed && installations.length === 0 && (
        <p className="rounded-[9px] border border-dashed border-black/[0.12] px-3 py-6 text-center text-[13px] text-black/50 dark:border-white/[0.14] dark:text-white/50">
          The Plot GitHub App is not installed on any other account you can reach.
        </p>
      )}
      {!loading && !failed && installations.length > 0 && (
        <ul className="divide-y divide-black/[0.07] rounded-[9px] border border-black/10 dark:divide-white/[0.08] dark:border-white/12" aria-label="GitHub accounts">
          {installations.map((installation) => {
            const organization = installation.accountType.toLowerCase() === "organization";
            const connecting = connectingInstallationId === installation.installationId;
            return (
              <li key={installation.installationId} className="flex items-center gap-3 px-3 py-2.5">
                <GitHubAccountAvatar login={installation.accountLogin} label={installation.accountLogin} size={28} />
                <div className="min-w-0 flex-1">
                  <div className="flex min-w-0 items-center gap-2">
                    <span className="truncate text-[13px] font-medium text-black/80 dark:text-white/84">{installation.accountLogin}</span>
                    <span className="shrink-0 rounded-full bg-black/[0.05] px-1.5 py-px text-[10px] font-medium text-black/50 dark:bg-white/[0.08] dark:text-white/55">
                      {organization ? "Organization" : "Personal"}
                    </span>
                  </div>
                  {!installation.connected && !installation.connectable && (
                    <p className="text-[11px] text-black/42 dark:text-white/45">Only {installation.accountLogin} can connect this personal account.</p>
                  )}
                </div>
                {installation.connected ? (
                  <span className="shrink-0 text-[12px] text-black/40 dark:text-white/42">Connected</span>
                ) : installation.connectable && (
                  <button
                    type="button"
                    onClick={() => onConnect(installation)}
                    disabled={busy}
                    aria-busy={connecting}
                    aria-label={`Connect ${installation.accountLogin}`}
                    className="glass-button inline-flex shrink-0 items-center gap-1.5 disabled:cursor-wait"
                  >
                    {connecting && <LoaderCircle className="size-3.5 animate-spin" aria-hidden="true" />}
                    Connect
                  </button>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </IntegrationDialog>
  );
}
