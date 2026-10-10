"use client";

import { ArrowLeft, LoaderCircle, LockKeyhole } from "lucide-react";
import {
  Add01Icon,
  Cancel01Icon,
  GithubIcon,
  Link01Icon,
  Unlink04Icon,
} from "@hugeicons/core-free-icons";
import { HugeiconsIcon } from "@hugeicons/react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useEffect, useRef, useState, type ReactNode } from "react";

import {
  WorkspaceHeader,
  workspaceIconButtonClass,
  workspacePageClass,
  workspaceSectionClass,
} from "@/components/layout/workspace-page";
import {
  getSelectedWorkspaceId,
  plotApiClient,
  PlotApiError,
  type GitHubAvailableInstallation,
  type GitHubConnection,
  type GitHubRepository,
} from "@/lib/api-client";
import { useWorkspaceChanged } from "@/lib/workspace-changed";

import {
  AddGitHubAccountDialog,
  ConnectGitHubDialog,
  GitHubAccountAvatar,
  SelectRepositoriesDialog,
} from "./github-connection-dialogs";
import { connectionIsRemoved, githubConnectionStatus, type GitHubConnectionStatus } from "./github-connection-status";
import { BrandIcon } from "./integration-brand-icon";

type IntegrationAction = "install" | "reauthenticate" | "manage" | "add" | "disconnect" | "remove" | "connect" | null;

const githubPagePath = "/settings/integrations/github";
const initialImportDays = 30;

function requestIsCurrent(controller: AbortController, workspaceId: string) {
  return !controller.signal.aborted && getSelectedWorkspaceId() === workspaceId;
}

/** Repositories the owner removed are hidden; ones GitHub took away stay visible so the cause is explained. */
function listedRepositories(connection: GitHubConnection) {
  return connection.repositories.filter((repository) => repository.id && (
    repository.status === "ACTIVE"
    || (repository.statusReason && repository.statusReason !== "USER_DISCONNECTED")
  ));
}

function accountLogin(connection: GitHubConnection) {
  return connection.accountLogin
    ?? connection.repositories.find((repository) => repository.owner)?.owner
    ?? null;
}

function accountLabel(connection: GitHubConnection) {
  return accountLogin(connection) ?? `Installation ${connection.installationId}`;
}


export function GitHubIntegrationWorkspace() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const callbackConnectionId = searchParams.get("githubConnection");
  const callbackError = searchParams.get("githubError");
  const callbackConnected = searchParams.get("githubConnected") === "1";
  const connectRequested = searchParams.get("connect") === "1";
  const pendingConnectRef = useRef(connectRequested);
  const [preferredConnectionId] = useState(callbackConnectionId);
  const autoOpenRepositoriesRef = useRef(Boolean(callbackConnectionId || callbackConnected));
  const [connections, setConnections] = useState<GitHubConnection[]>([]);
  const [isOwner, setIsOwner] = useState<boolean | null>(null);
  const [connectionNeedsReconnect, setConnectionNeedsReconnect] = useState(false);
  const [isLoading, setIsLoading] = useState(true);
  const [action, setAction] = useState<IntegrationAction>(null);
  const actionRef = useRef<IntegrationAction>(null);
  const actionAbortRef = useRef<AbortController | null>(null);
  const [disconnectingRepositoryId, setDisconnectingRepositoryId] = useState<string | null>(null);
  const [removingConnectionId, setRemovingConnectionId] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(callbackError ? callbackMessage(callbackError) : null);
  const [messageRequestId, setMessageRequestId] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(callbackConnected ? "GitHub account connected." : null);
  const [reloadNonce, setReloadNonce] = useState(0);
  const [connectDialogOpen, setConnectDialogOpen] = useState(false);
  const [repositoryDialogConnectionId, setRepositoryDialogConnectionId] = useState<string | null>(null);
  const [addAccountOpen, setAddAccountOpen] = useState(false);
  const [addAccountError, setAddAccountError] = useState<string | null>(null);
  const [connectingInstallationId, setConnectingInstallationId] = useState<number | null>(null);
  const pendingRepositoriesConnectionRef = useRef<string | null>(null);

  const currentConnections = connections.filter((connection) => !connectionIsRemoved(connection));
  const connectionStatus = githubConnectionStatus(connections, connectionNeedsReconnect);
  const firstActiveConnection = currentConnections.find((connection) => connection.status === "ACTIVE") ?? null;
  const repositoryDialogConnection = connections.find((connection) => connection.id === repositoryDialogConnectionId) ?? null;
  const canConnect = connectionStatus === "none" || connectionStatus === "disconnected";

  const clearFeedback = () => {
    setMessage(null);
    setMessageRequestId(null);
    setNotice(null);
  };

  const refresh = () => {
    clearFeedback();
    setIsLoading(true);
    setReloadNonce((value) => value + 1);
  };

  useWorkspaceChanged(() => {
    setConnections([]);
    setIsOwner(null);
    setConnectionNeedsReconnect(false);
    setMessage(null);
    setMessageRequestId(null);
    setNotice(null);
    setConnectDialogOpen(false);
    setRepositoryDialogConnectionId(null);
    setAddAccountOpen(false);
    setAddAccountError(null);
    setConnectingInstallationId(null);
    pendingRepositoriesConnectionRef.current = null;
    actionAbortRef.current?.abort();
    actionAbortRef.current = null;
    actionRef.current = null;
    setAction(null);
    setDisconnectingRepositoryId(null);
    setRemovingConnectionId(null);
    setIsLoading(true);
    setReloadNonce((value) => value + 1);
  });

  useEffect(() => {
    const controller = new AbortController();

    async function load() {
      try {
        const workspaceId = getSelectedWorkspaceId();
        if (!workspaceId) throw new Error("No workspace is selected");

        const workspaceRequest = plotApiClient.getWorkspace(workspaceId, { signal: controller.signal });
        const connectionsRequest = plotApiClient.listGitHubConnections({ signal: controller.signal });
        connectionsRequest.catch(() => undefined);
        const workspace = await workspaceRequest;
        if (!requestIsCurrent(controller, workspaceId)) return;
        // Keep owner controls available even when GitHub itself cannot be reached.
        const owner = workspace.role === "OWNER";
        setIsOwner(owner);

        const nextConnections = await connectionsRequest;
        if (!requestIsCurrent(controller, workspaceId)) return;
        setConnections(nextConnections);
        setConnectionNeedsReconnect(false);

        // Right after installing the GitHub App, go straight to choosing repositories.
        if (autoOpenRepositoriesRef.current || pendingRepositoriesConnectionRef.current) {
          const targetId = pendingRepositoriesConnectionRef.current ?? preferredConnectionId;
          autoOpenRepositoriesRef.current = false;
          pendingRepositoriesConnectionRef.current = null;
          const installed = nextConnections.find((connection) => connection.id === targetId)
            ?? nextConnections.find((connection) => connection.status === "ACTIVE");
          const needsRepository = installed?.status === "ACTIVE"
            && !installed.repositories.some((repository) => repository.status === "ACTIVE");
          if (owner && installed && needsRepository) setRepositoryDialogConnectionId(installed.id);
        }
      } catch (error) {
        if (!controller.signal.aborted) {
          setConnectionNeedsReconnect(requiresReconnect(error));
          setMessage(errorMessage(error));
          setMessageRequestId(providerRequestId(error));
        }
      } finally {
        if (!controller.signal.aborted) setIsLoading(false);
      }
    }

    queueMicrotask(() => { void load(); });
    return () => controller.abort();
  }, [preferredConnectionId, reloadNonce]);

  useEffect(() => {
    if (callbackConnectionId || callbackError || callbackConnected || connectRequested) router.replace(githubPagePath);
  }, [callbackConnectionId, callbackError, callbackConnected, connectRequested, router]);

  // The Connections catalog sends owners here with ?connect=1 to start installing right away.
  useEffect(() => {
    if (!pendingConnectRef.current || isLoading) return;
    pendingConnectRef.current = false;
    if (isOwner && canConnect) queueMicrotask(() => setConnectDialogOpen(true));
  }, [isLoading, isOwner, canConnect]);

  const installGitHub = async () => {
    if (actionRef.current) return;
    actionRef.current = "install";
    setAction("install");
    clearFeedback();
    setConnectionNeedsReconnect(false);

    try {
      // Link an installation the user already made on GitHub before sending them there again.
      await plotApiClient.syncGitHubInstallation();
      setConnectDialogOpen(false);
      autoOpenRepositoriesRef.current = true;
      setReloadNonce((value) => value + 1);
      setNotice("GitHub App connected.");
      return;
    } catch (error) {
      if (error instanceof PlotApiError && error.code === "GITHUB_INSTALLATION_NOT_FOUND") {
        try {
          const request = await plotApiClient.createGitHubInstallationRequest();
          window.location.assign(request.installUrl);
          return;
        } catch (installError) {
          error = installError;
        }
      }

      setConnectDialogOpen(false);
      setConnectionNeedsReconnect(requiresReconnect(error));
      setMessage(errorMessage(error));
      setMessageRequestId(providerRequestId(error));
    } finally {
      actionRef.current = null;
      setAction(null);
    }
  };

  const manageGitHubAccess = async () => {
    if (actionRef.current) return;
    actionRef.current = "manage";
    setAction("manage");
    clearFeedback();
    try {
      const request = await plotApiClient.createGitHubInstallationRequest();
      window.location.assign(request.installUrl);
    } catch (error) {
      setRepositoryDialogConnectionId(null);
      setAddAccountOpen(false);
      setMessage(errorMessage(error));
      setMessageRequestId(providerRequestId(error));
      actionRef.current = null;
      setAction(null);
    }
  };

  const reauthenticateGitHub = async () => {
    if (actionRef.current) return;
    actionRef.current = "reauthenticate";
    setAction("reauthenticate");
    setMessage(null);
    try {
      const request = await plotApiClient.startGitHubProductOAuth("/settings/integrations");
      window.location.assign(request.authorizationUrl);
    } catch (error) {
      setMessage(errorMessage(error));
      setMessageRequestId(providerRequestId(error));
      actionRef.current = null;
      setAction(null);
    }
  };

  const loadGrantedRepositories = async (connection: GitHubConnection, signal: AbortSignal) => {
    try {
      return await plotApiClient.listGitHubRepositories(connection.id, { signal });
    } catch (error) {
      // The API marks the installation for reauthorization; reflect that on the card instead of inside the dialog.
      if (!signal.aborted && requiresReconnect(error)) {
        setRepositoryDialogConnectionId(null);
        setConnections((current) => current.map((item) => item.id === connection.id
          ? { ...item, status: "NEEDS_REAUTH", statusReason: "AUTH_EXPIRED" }
          : item));
        setMessage(errorMessage(error));
        setMessageRequestId(providerRequestId(error));
      }
      throw error;
    }
  };

  const addRepositories = async (connection: GitHubConnection, repositories: GitHubRepository[]) => {
    if (!repositories.length || actionRef.current) return;
    const workspaceId = getSelectedWorkspaceId();
    if (!workspaceId) return;
    const controller = new AbortController();
    actionAbortRef.current = controller;
    actionRef.current = "add";
    setAction("add");
    clearFeedback();

    const added: GitHubRepository[] = [];
    let importError: unknown = null;
    let connectError: unknown = null;
    try {
      const to = new Date();
      const from = new Date(to.getTime() - initialImportDays * 24 * 60 * 60 * 1_000);
      for (const repository of repositories) {
        try {
          const connected = await plotApiClient.connectGitHubRepository(
            connection.id,
            repository.externalRepositoryId,
            { signal: controller.signal },
          );
          if (!requestIsCurrent(controller, workspaceId)) return;
          if (!connected.id) continue;
          added.push(connected);
          try {
            await plotApiClient.importGitHubRepository(connected.id, {
              from: from.toISOString(),
              to: to.toISOString(),
            }, { signal: controller.signal });
          } catch (error) {
            importError ??= error;
          }
          if (!requestIsCurrent(controller, workspaceId)) return;
        } catch (error) {
          connectError = error;
          break;
        }
      }
      if (!requestIsCurrent(controller, workspaceId)) return;

      if (added.length) {
        const addedIds = new Set(added.map((repository) => repository.externalRepositoryId));
        setConnections((current) => current.map((item) => item.id === connection.id
          ? { ...item, repositories: [...item.repositories.filter((repository) => !addedIds.has(repository.externalRepositoryId)), ...added] }
          : item));
      }
      setRepositoryDialogConnectionId(null);
      if (connectError) {
        setMessage(added.length
          ? `${repositoryCount(added.length)} added, but the rest could not be added. ${errorMessage(connectError)}`
          : errorMessage(connectError));
        setMessageRequestId(providerRequestId(connectError));
      } else if (importError) {
        setMessage("GitHub is connected, but the initial source import could not finish.");
        setMessageRequestId(providerRequestId(importError));
      } else if (added.length) {
        setNotice(`${repositoryCount(added.length)} added. Plot is importing the last ${initialImportDays} days of activity.`);
      }
    } finally {
      if (actionAbortRef.current === controller) {
        actionAbortRef.current = null;
        actionRef.current = null;
        setAction(null);
      }
    }
  };

  const showAccountReconnect = (error: unknown) => {
    setAddAccountOpen(false);
    setConnectionNeedsReconnect(true);
    setMessage(errorMessage(error));
    setMessageRequestId(providerRequestId(error));
  };

  const loadAvailableInstallations = async (signal: AbortSignal) => {
    try {
      return await plotApiClient.listGitHubAvailableInstallations({ signal });
    } catch (error) {
      if (!signal.aborted && error instanceof PlotApiError && error.code === "GITHUB_ACCOUNT_NOT_LINKED") showAccountReconnect(error);
      throw error;
    }
  };

  const connectInstallation = async (installation: GitHubAvailableInstallation) => {
    if (actionRef.current) return;
    const workspaceId = getSelectedWorkspaceId();
    if (!workspaceId) return;
    const controller = new AbortController();
    actionAbortRef.current = controller;
    actionRef.current = "connect";
    setAction("connect");
    setConnectingInstallationId(installation.installationId);
    setAddAccountError(null);
    clearFeedback();
    try {
      const result = await plotApiClient.connectGitHubInstallation(installation.installationId, { signal: controller.signal });
      if (!requestIsCurrent(controller, workspaceId)) return;
      setAddAccountOpen(false);
      pendingRepositoriesConnectionRef.current = result.connectionId;
      setNotice(`Connected ${installation.accountLogin}.`);
      setReloadNonce((value) => value + 1);
    } catch (error) {
      if (!requestIsCurrent(controller, workspaceId)) return;
      if (error instanceof PlotApiError && error.code === "GITHUB_ACCOUNT_NOT_LINKED") {
        showAccountReconnect(error);
      } else {
        setAddAccountError(installationErrorMessage(error, installation.accountLogin));
      }
    } finally {
      if (actionAbortRef.current === controller) {
        actionAbortRef.current = null;
        actionRef.current = null;
        setAction(null);
        setConnectingInstallationId(null);
      }
    }
  };

  const removeConnection = async (connection: GitHubConnection) => {
    if (actionRef.current) return;
    const workspaceId = getSelectedWorkspaceId();
    if (!workspaceId) return;
    const controller = new AbortController();
    actionAbortRef.current = controller;
    actionRef.current = "remove";
    setAction("remove");
    setRemovingConnectionId(connection.id);
    clearFeedback();
    try {
      await plotApiClient.removeGitHubConnection(connection.id, { signal: controller.signal });
      if (!requestIsCurrent(controller, workspaceId)) return;
      setConnections((current) => current.map((item) => item.id === connection.id
        ? { ...item, status: "DISABLED" }
        : item));
      setConnectionNeedsReconnect(false);
      setNotice(`Removed the unavailable ${accountLabel(connection)} connection.`);
    } catch (error) {
      if (requestIsCurrent(controller, workspaceId)) {
        setMessage(errorMessage(error));
        setMessageRequestId(providerRequestId(error));
      }
    } finally {
      if (actionAbortRef.current === controller) {
        actionAbortRef.current = null;
        actionRef.current = null;
        setAction(null);
        setRemovingConnectionId(null);
      }
    }
  };

  const disconnectRepository = async (connection: GitHubConnection, repository: GitHubRepository) => {
    if (!repository.id || actionRef.current) return;
    const repositoryId = repository.id;
    const workspaceId = getSelectedWorkspaceId();
    if (!workspaceId) return;
    const controller = new AbortController();
    actionAbortRef.current = controller;
    actionRef.current = "disconnect";
    setAction("disconnect");
    setDisconnectingRepositoryId(repositoryId);
    clearFeedback();
    try {
      await plotApiClient.disconnectGitHubRepository(repositoryId, { signal: controller.signal });
      if (!requestIsCurrent(controller, workspaceId)) return;
      setConnections((current) => current.map((item) => item.id === connection.id
        ? {
          ...item,
          repositories: item.repositories.map((candidate) => candidate.id === repositoryId
            ? { ...candidate, status: "DISABLED", statusReason: "USER_DISCONNECTED" }
            : candidate),
        }
        : item));
      setNotice(`${repository.displayName} disconnected. Plot no longer follows its activity.`);
    } catch (error) {
      if (requestIsCurrent(controller, workspaceId)) {
        setMessage(errorMessage(error));
        setMessageRequestId(providerRequestId(error));
      }
    } finally {
      if (actionAbortRef.current === controller) {
        actionAbortRef.current = null;
        actionRef.current = null;
        setAction(null);
        setDisconnectingRepositoryId(null);
      }
    }
  };

  const openRepositories = (connection: GitHubConnection) => {
    clearFeedback();
    setRepositoryDialogConnectionId(connection.id);
  };

  let headerAction: ReactNode = null;
  if (!isLoading && isOwner) {
    if (canConnect) {
      headerAction = (
        <button
          type="button"
          onClick={() => setConnectDialogOpen(true)}
          disabled={action !== null}
          className="glass-button glass-primary inline-flex items-center gap-1.5"
        >
          <HugeiconsIcon icon={GithubIcon} size={15} color="currentColor" strokeWidth={1.6} aria-hidden="true" />
          Connect GitHub
        </button>
      );
    } else if (connectionStatus === "attention" && currentConnections.length > 0) {
      headerAction = (
        <button
          type="button"
          onClick={() => { void installGitHub(); }}
          disabled={action !== null}
          aria-busy={action === "install"}
          className="glass-button glass-primary inline-flex items-center gap-1.5 disabled:cursor-wait"
        >
          {action === "install"
            ? <LoaderCircle className="size-3.5 animate-spin" aria-hidden="true" />
            : <HugeiconsIcon icon={Link01Icon} size={15} color="currentColor" strokeWidth={1.5} aria-hidden="true" />}
          Reconnect GitHub
        </button>
      );
    } else if (firstActiveConnection) {
      headerAction = (
        <button
          type="button"
          onClick={() => openRepositories(firstActiveConnection)}
          disabled={action !== null}
          className="glass-button glass-primary inline-flex items-center gap-1.5"
        >
          <HugeiconsIcon icon={Add01Icon} size={15} color="currentColor" strokeWidth={1.6} aria-hidden="true" />
          Add repositories
        </button>
      );
    }
  }

  return (
    <div className={workspacePageClass}>
      <section className={workspaceSectionClass} aria-labelledby="github-heading">
        <div className="px-6 pt-6">
          <Link
            href="/settings/integrations"
            className="inline-flex items-center gap-1 text-[12px] text-black/45 transition hover:text-black/75 dark:text-white/45 dark:hover:text-white/75"
          >
            <ArrowLeft className="size-3.5" aria-hidden="true" />
            Connections
          </Link>
        </div>
        <WorkspaceHeader
          id="github-heading"
          title="GitHub"
          description="Connect repositories through the Plot GitHub App so Plot can draft source-backed updates from releases, merged pull requests, and commits."
          actions={headerAction}
        />

        <div className="space-y-4 px-6 py-6 pb-10">
          {!isLoading && message && (
            <div>
              <StatusMessage
                message={message}
                requestId={messageRequestId}
                onRetry={refresh}
                onDismiss={() => setMessage(null)}
              />
              {connectionNeedsReconnect && (
                <button
                  type="button"
                  onClick={() => { void reauthenticateGitHub(); }}
                  aria-busy={action === "reauthenticate"}
                  disabled={action !== null}
                  className="glass-button glass-primary mt-3 inline-flex w-full items-center justify-center gap-1.5 disabled:cursor-wait"
                >
                  {action === "reauthenticate" ? <LoaderCircle className="size-3.5 animate-spin" aria-hidden="true" /> : <HugeiconsIcon icon={Link01Icon} size={14} color="currentColor" strokeWidth={1.5} aria-hidden="true" />}
                  Reconnect GitHub account
                </button>
              )}
            </div>
          )}
          {!isLoading && notice && !message && (
            <p role="status" className="glass-card flex items-start gap-2 rounded-[9px] border border-black/10 px-3 py-2.5 text-[12px] leading-5 text-black/58 dark:border-white/12 dark:text-white/60">
              <span className="min-w-0 flex-1">{notice}</span>
              <button className="glass-button glass-icon -my-1" type="button" onClick={() => setNotice(null)} aria-label="Dismiss notice">
                <HugeiconsIcon icon={Cancel01Icon} size={14} aria-hidden="true" />
              </button>
            </p>
          )}

          {isLoading && <Loading />}
          {!isLoading && currentConnections.length === 0 && (
            <NoAccountState
              disconnected={connectionStatus === "disconnected"}
              canConnect={isOwner === true}
              busy={action !== null}
              onConnect={() => setConnectDialogOpen(true)}
            />
          )}
          {!isLoading && currentConnections.map((connection) => (
            <GitHubAccount
              key={connection.id}
              connection={connection}
              reconnectRequired={connectionNeedsReconnect}
              canManage={isOwner === true}
              busy={action !== null}
              disconnectingRepositoryId={disconnectingRepositoryId}
              removing={removingConnectionId === connection.id}
              onRemove={() => { void removeConnection(connection); }}
              onAddRepositories={() => openRepositories(connection)}
              onDisconnectRepository={(repository) => { void disconnectRepository(connection, repository); }}
            />
          ))}
          {!isLoading && isOwner === false && <NonOwnerState connected={connectionStatus === "connected"} />}
        </div>
      </section>

      <ConnectGitHubDialog
        open={connectDialogOpen}
        connecting={action === "install"}
        onClose={() => setConnectDialogOpen(false)}
        onConnect={() => { void installGitHub(); }}
      />
      <SelectRepositoriesDialog
        open={repositoryDialogConnection !== null}
        accounts={currentConnections
          .filter((connection) => connection.status === "ACTIVE")
          .map((connection) => ({ id: connection.id, login: accountLogin(connection), label: accountLabel(connection) }))}
        accountId={repositoryDialogConnection?.id ?? ""}
        loadRepositories={(signal) => repositoryDialogConnection
          ? loadGrantedRepositories(repositoryDialogConnection, signal)
          : Promise.resolve([])}
        saving={action === "add" || action === "manage"}
        onClose={() => setRepositoryDialogConnectionId(null)}
        onSave={(repositories) => {
          if (repositoryDialogConnection) void addRepositories(repositoryDialogConnection, repositories);
        }}
        onSelectAccount={setRepositoryDialogConnectionId}
        onAddAccount={() => {
          setRepositoryDialogConnectionId(null);
          setAddAccountError(null);
          setAddAccountOpen(true);
        }}
        onManageAccess={() => { void manageGitHubAccess(); }}
      />
      <AddGitHubAccountDialog
        open={addAccountOpen}
        loadInstallations={loadAvailableInstallations}
        connectingInstallationId={connectingInstallationId}
        error={addAccountError}
        installing={action === "manage"}
        onClose={() => setAddAccountOpen(false)}
        onConnect={(installation) => { void connectInstallation(installation); }}
        onInstallElsewhere={() => { void manageGitHubAccess(); }}
      />
    </div>
  );
}

function NoAccountState({
  disconnected,
  canConnect,
  busy,
  onConnect,
}: {
  disconnected: boolean;
  canConnect: boolean;
  busy: boolean;
  onConnect: () => void;
}) {
  return (
    <div className="flex flex-col items-center rounded-[12px] border border-dashed border-black/[0.12] px-6 py-12 text-center dark:border-white/[0.14]">
      <BrandIcon brand="github" />
      <p className="mt-4 text-[14px] font-semibold text-black/80 dark:text-white/84">
        {disconnected ? "GitHub was disconnected" : "No GitHub account connected"}
      </p>
      <p className="mt-1 max-w-[360px] text-[13px] leading-5 text-black/48 dark:text-white/50">
        {disconnected
          ? "The Plot GitHub App was uninstalled. Install it again to keep following your repositories."
          : "Install the Plot GitHub App to get started."}
      </p>
      {canConnect && (
        <button
          type="button"
          onClick={onConnect}
          disabled={busy}
          className="glass-button mt-5 inline-flex items-center gap-1.5"
        >
          <HugeiconsIcon icon={Add01Icon} size={14} color="currentColor" strokeWidth={1.6} aria-hidden="true" />
          Connect GitHub
        </button>
      )}
    </div>
  );
}

function GitHubAccount({
  connection,
  reconnectRequired,
  canManage,
  busy,
  disconnectingRepositoryId,
  removing,
  onRemove,
  onAddRepositories,
  onDisconnectRepository,
}: {
  connection: GitHubConnection;
  reconnectRequired: boolean;
  canManage: boolean;
  busy: boolean;
  disconnectingRepositoryId: string | null;
  removing: boolean;
  onRemove: () => void;
  onAddRepositories: () => void;
  onDisconnectRepository: (repository: GitHubRepository) => void;
}) {
  const label = accountLabel(connection);
  const repositories = listedRepositories(connection);
  const activeCount = repositories.filter((repository) => repository.status === "ACTIVE").length;
  const active = connection.status === "ACTIVE";
  const canEdit = canManage && active;

  return (
    <article className="glass-card rounded-[12px] border border-black/10 dark:border-white/12" aria-label={`${label} GitHub account`}>
      <header className="flex items-center gap-3 border-b border-black/[0.07] px-4 py-3.5 dark:border-white/[0.08]">
        <GitHubAccountAvatar login={accountLogin(connection)} label={label} />
        <div className="min-w-0 flex-1">
          <div className="flex min-w-0 items-center gap-2">
            <h2 className="truncate text-[14px] font-semibold text-black/82 dark:text-white/86">{label}</h2>
            <ConnectionBadge status={active ? (reconnectRequired ? "attention" : "connected") : "attention"} />
          </div>
          <p className={`text-[12px] ${active ? "text-black/42 dark:text-white/45" : "text-amber-700 dark:text-amber-300"}`}>
            {active ? `GitHub App installation ${connection.installationId}` : "GitHub no longer accepts this installation"}
          </p>
        </div>
        {canManage && !active && (
          <button
            type="button"
            onClick={onRemove}
            disabled={busy}
            aria-busy={removing}
            aria-label={`Remove ${label} connection`}
            className="glass-button glass-danger inline-flex shrink-0 items-center gap-1.5"
          >
            {removing && <LoaderCircle className="size-3.5 animate-spin" aria-hidden="true" />}
            Remove
          </button>
        )}
      </header>

      <div className="space-y-3 p-4">
        <div className="flex items-center justify-between gap-3">
          <p className="text-[13px] font-medium text-black/72 dark:text-white/76">
            Connected repositories
            <span className="ml-1.5 text-black/40 dark:text-white/42">{activeCount}</span>
          </p>
          {canEdit && (
            <button
              type="button"
              onClick={onAddRepositories}
              disabled={busy}
              className="glass-button inline-flex shrink-0 items-center gap-1.5"
            >
              <HugeiconsIcon icon={Add01Icon} size={14} color="currentColor" strokeWidth={1.6} aria-hidden="true" />
              Add repositories
            </button>
          )}
        </div>

      {repositories.length === 0 ? (
        <p className="rounded-[9px] border border-dashed border-black/[0.12] px-3 py-6 text-center text-[13px] text-black/48 dark:border-white/[0.14] dark:text-white/48">
          {canEdit ? "Add a repository so Plot can follow its releases and changes." : "No repositories selected yet."}
        </p>
      ) : (
        <ul className="divide-y divide-black/[0.07] rounded-[9px] border border-black/10 dark:divide-white/[0.08] dark:border-white/12" aria-label={`${label} repositories`}>
          {repositories.map((repository) => {
            const active = repository.status === "ACTIVE";
            const disconnecting = disconnectingRepositoryId === repository.id;
            const detail = active ? monitoringLabel(repository) : repositoryStatusLabel(repository.statusReason);
            return (
              <li key={repository.id} className="flex min-h-11 items-center gap-3 px-3 py-2">
                <HugeiconsIcon icon={GithubIcon} size={15} color="currentColor" strokeWidth={1.5} aria-hidden="true" className="shrink-0 text-black/40 dark:text-white/42" />
                <div className="flex min-w-0 flex-1 items-center gap-1.5">
                  <a
                    href={repository.url}
                    target="_blank"
                    rel="noreferrer"
                    className={`truncate text-[13px] font-medium underline-offset-4 hover:underline ${active ? "text-black/78 dark:text-white/82" : "text-black/42 dark:text-white/45"}`}
                  >
                    {repository.displayName}
                  </a>
                  {repository.visibility === "PRIVATE" && (
                    <>
                      <LockKeyhole className="size-3.5 shrink-0 text-black/38 dark:text-white/40" aria-hidden="true" />
                      <span className="sr-only">Private repository</span>
                    </>
                  )}
                </div>
                {detail && (
                  <span className={`shrink-0 text-[11px] ${active ? "text-black/42 dark:text-white/45" : "text-amber-700 dark:text-amber-300"}`}>{detail}</span>
                )}
                {canEdit && active && (
                  <button
                    type="button"
                    onClick={() => onDisconnectRepository(repository)}
                    disabled={busy}
                    aria-busy={disconnecting}
                    aria-label={`Disconnect ${repository.displayName}`}
                    title={`Disconnect ${repository.displayName}`}
                    className={`glass-button glass-icon glass-danger ${workspaceIconButtonClass} size-8 shrink-0`}
                  >
                    {disconnecting
                      ? <LoaderCircle className="size-3.5 animate-spin" aria-hidden="true" />
                      : <HugeiconsIcon icon={Unlink04Icon} size={15} color="currentColor" strokeWidth={1.5} aria-hidden="true" />}
                  </button>
                )}
              </li>
            );
          })}
        </ul>
      )}
      </div>
    </article>
  );
}

function repositoryCount(count: number) {
  return `${count} ${count === 1 ? "repository" : "repositories"}`;
}

function monitoringLabel(repository: GitHubRepository) {
  const status = repository.monitoring?.analysisStatus;
  if (status === "QUEUED" || status === "ANALYZING") return "Analyzing releases…";
  return null;
}

function repositoryStatusLabel(reason: GitHubRepository["statusReason"]) {
  if (reason === "GRANT_REMOVED") return "Access removed on GitHub";
  if (reason === "REPOSITORY_DELETED") return "Deleted on GitHub";
  if (reason === "REPOSITORY_TRANSFERRED") return "Transferred on GitHub";
  return "Unavailable";
}

function ConnectionBadge({ status }: { status: GitHubConnectionStatus }) {
  if (status === "none") return null;

  const styles = status === "connected"
    ? "bg-emerald-50 text-emerald-700 dark:bg-emerald-500/10 dark:text-emerald-300"
    : status === "attention"
      ? "bg-amber-50 text-amber-800 dark:bg-amber-500/10 dark:text-amber-200"
      : "bg-black/[0.05] text-black/45 dark:bg-white/10 dark:text-white/48";
  return (
    <span className={`shrink-0 rounded-full px-2 py-0.5 text-[11px] font-medium ${styles}`}>
      {status === "connected" ? "Connected" : status === "attention" ? "Needs attention" : "Disconnected"}
    </span>
  );
}

function NonOwnerState({ connected }: { connected: boolean }) {
  return (
    <div className="glass-card rounded-[10px] border border-black/10 p-4 text-sm dark:border-white/10">
      <div className="font-medium text-black/78 dark:text-white/80">
        GitHub is {connected ? "connected" : "not connected"} for this workspace
      </div>
      <p className="mt-1 text-black/52 dark:text-white/52">
        {connected ? "Only the workspace owner can add or disconnect repositories." : "Ask the workspace owner to connect GitHub."}
      </p>
    </div>
  );
}

function Loading() {
  return (
    <div className="flex items-center gap-2 py-6 text-sm text-black/50 dark:text-white/50">
      <LoaderCircle className="size-4 animate-spin" />
      Loading GitHub integration…
    </div>
  );
}

function StatusMessage({
  message,
  requestId,
  onRetry,
  onDismiss,
}: {
  message: string;
  requestId: string | null;
  onRetry: () => void;
  onDismiss: () => void;
}) {
  return (
    <div role="alert" className="flex items-start gap-3 rounded-[10px] border border-amber-300 bg-amber-50 p-3 text-sm text-amber-900 dark:border-amber-400/30 dark:bg-amber-500/10 dark:text-amber-100">
      <div className="min-w-0 flex-1">
        <p>{message}</p>
        {requestId && (
          <details className="mt-1 text-xs">
            <summary className="cursor-pointer font-medium">Technical details</summary>
            <code>GitHub request {requestId}</code>
          </details>
        )}
      </div>
      <button type="button" onClick={onRetry} className="glass-button shrink-0 underline underline-offset-2">Retry</button>
      <button className="glass-button glass-icon" type="button" onClick={onDismiss} aria-label="Dismiss message">
        <HugeiconsIcon icon={Cancel01Icon} size={16} aria-hidden="true" />
      </button>
    </div>
  );
}

function callbackMessage(value: string) {
  if (value === "invalid") return "The GitHub installation link expired. Try connecting again.";
  if (value === "unauthorized") return "Only the workspace owner can connect GitHub.";
  if (value === "unavailable") return "GitHub is temporarily unavailable. Try again shortly.";
  if (value === "failed") return "GitHub account authorization could not be completed. Try again.";
  return "GitHub could not be connected. Try again.";
}

function installationErrorMessage(error: unknown, accountLogin: string) {
  if (error instanceof PlotApiError) {
    if (error.code === "GITHUB_INSTALLATION_NOT_OWNED") return `You need to be an owner of ${accountLogin} on GitHub to connect it.`;
    if (error.code === "GITHUB_REAUTH_REQUIRED") {
      return `Plot could not confirm you are an owner of ${accountLogin}. If ${accountLogin} has a pending permission request for the Plot GitHub App, approve it in GitHub settings, then try again.`;
    }
    if (error.code === "GITHUB_INSTALLATION_NOT_FOUND") return `The Plot GitHub App is no longer installed on ${accountLogin}.`;
  }
  return errorMessage(error);
}

function errorMessage(error: unknown) {
  if (error instanceof PlotApiError) {
    if (error.code === "GITHUB_NOT_CONFIGURED") return "GitHub is not configured for this environment. Try again after an administrator enables it.";
    if (error.code === "GITHUB_RATE_LIMITED") return "GitHub rate limit reached. Wait a moment, then retry.";
    if (error.code === "GITHUB_NOT_FOUND") return "The previous GitHub installation was replaced or removed. Reconnect GitHub to restore access.";
    if (error.code === "GITHUB_ACCESS_DENIED" || error.code === "CONNECTION_INACTIVE" || error.code === "REPOSITORY_INACTIVE") return "GitHub access was revoked. Reconnect GitHub, then retry.";
    if (error.code === "GITHUB_REAUTH_REQUIRED") return "GitHub authorization must be refreshed. Connect GitHub again to grant organization access.";
    if (error.code === "GITHUB_INSTALLATION_AMBIGUOUS") return "Multiple GitHub App installations found. Visit GitHub settings to manage your installations, then reconnect the account you want to use.";
    if (error.code === "GITHUB_ACCOUNT_NOT_LINKED") return "No linked GitHub account found. Connect a GitHub account first, then retry.";
    if (error.code === "GITHUB_PRODUCT_OAUTH_NOT_CONFIGURED") return "GitHub account authorization is not configured for this environment.";
    if (error.code === "FORBIDDEN") return "Workspace owner must connect GitHub.";
    if (error.code === "GITHUB_PROVIDER_UNAVAILABLE") return "GitHub is temporarily unavailable. Try again shortly.";
    return "GitHub request failed. Try again.";
  }
  return "Could not update the GitHub connection. Try again.";
}

function providerRequestId(error: unknown) {
  if (!(error instanceof PlotApiError)) return null;
  return error.message.match(/\(request ([A-Za-z0-9:_-]{1,100})\)/)?.[1] ?? null;
}

function requiresReconnect(error: unknown) {
  return error instanceof PlotApiError && new Set([
    "GITHUB_NOT_FOUND",
    "GITHUB_ACCESS_DENIED",
    "GITHUB_REAUTH_REQUIRED",
    "GITHUB_ACCOUNT_NOT_LINKED",
    "GITHUB_INSTALLATION_AMBIGUOUS",
    "CONNECTION_INACTIVE",
    "REPOSITORY_INACTIVE",
  ]).has(error.code);
}
