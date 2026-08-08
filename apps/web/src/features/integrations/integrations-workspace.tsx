"use client";

import { LoaderCircle } from "lucide-react";
import {
  Cancel01Icon,
  Link01Icon,
  Search01Icon,
  Unlink04Icon,
} from "@hugeicons/core-free-icons";
import { HugeiconsIcon } from "@hugeicons/react";
import { useRouter, useSearchParams } from "next/navigation";
import { useEffect, useRef, useState } from "react";

import {
  getSelectedWorkspaceId,
  plotApiClient,
  PlotApiError,
  type GitHubConnection,
} from "@/lib/api-client";

type IntegrationAction = "install" | "disconnect" | null;

type IntegrationBrand = "figma" | "github" | "linear" | "notion" | "slack";

const plannedIntegrations: Array<{
  brand: Exclude<IntegrationBrand, "github">;
  description: string;
  name: string;
}> = [
  {
    brand: "linear",
    description: "Bring product decisions, issue context, and project momentum into Plot.",
    name: "Linear",
  },
  {
    brand: "slack",
    description: "Turn important team conversations into durable source material.",
    name: "Slack",
  },
  {
    brand: "notion",
    description: "Reference the documents and decisions your workspace already maintains.",
    name: "Notion",
  },
  {
    brand: "figma",
    description: "Keep design context close to the artifacts it helped shape.",
    name: "Figma",
  },
];

export function IntegrationsWorkspace() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const callbackConnectionId = searchParams.get("githubConnection");
  const callbackError = searchParams.get("githubError");
  const [preferredConnectionId] = useState(callbackConnectionId);
  const [connections, setConnections] = useState<GitHubConnection[]>([]);
  const [isOwner, setIsOwner] = useState<boolean | null>(null);
  const [connectionNeedsReconnect, setConnectionNeedsReconnect] = useState(false);
  const [isLoading, setIsLoading] = useState(true);
  const [action, setAction] = useState<IntegrationAction>(null);
  const actionRef = useRef<IntegrationAction>(null);
  const [message, setMessage] = useState<string | null>(callbackError ? callbackMessage(callbackError) : null);
  const [messageRequestId, setMessageRequestId] = useState<string | null>(null);
  const [reloadNonce, setReloadNonce] = useState(0);
  const [integrationQuery, setIntegrationQuery] = useState("");

  const selectedConnection = connections.find((connection) => connection.id === preferredConnectionId)
    ?? connections.find((connection) => connection.status === "ACTIVE")
    ?? connections[0]
    ?? null;
  const selectedRepository = selectedConnection?.repositories.find((repository) => Boolean(repository.id)) ?? null;
  const selectedRepositoryDisconnected = selectedRepository?.statusReason === "REPOSITORY_DELETED"
    || selectedRepository?.statusReason === "USER_DISCONNECTED";
  const connectedRepository = selectedRepository?.status === "ACTIVE" ? selectedRepository : null;
  const connectionBadgeStatus: "connected" | "attention" | "disconnected" = !selectedConnection
    || selectedConnection.status === "DISABLED"
    || selectedConnection.statusReason === "INSTALLATION_UNINSTALLED"
    ? "disconnected"
    : selectedConnection.status !== "ACTIVE" || connectionNeedsReconnect
      ? "attention"
      : selectedRepositoryDisconnected
        ? "disconnected"
      : "connected";
  const normalizedIntegrationQuery = integrationQuery.trim().toLowerCase();
  const githubMatchesQuery = ["github", "repository", "release", "changelog"]
    .some((term) => term.includes(normalizedIntegrationQuery));
  const matchingPlannedIntegrations = plannedIntegrations.filter((integration) => (
    [integration.name, integration.description]
      .some((term) => term.toLowerCase().includes(normalizedIntegrationQuery))
  ));

  const refresh = () => {
    setMessage(null);
    setMessageRequestId(null);
    setIsLoading(true);
    setReloadNonce((value) => value + 1);
  };

  useEffect(() => {
    let cancelled = false;

    async function load() {
      try {
        const workspaceId = getSelectedWorkspaceId();
        if (!workspaceId) throw new Error("No workspace is selected");

        const [workspace, nextConnections] = await Promise.all([
          plotApiClient.getWorkspace(workspaceId),
          plotApiClient.listGitHubConnections(),
        ]);
        if (cancelled) return;

        const owner = workspace.role === "OWNER";
        setIsOwner(owner);
        setConnections(nextConnections);
        setConnectionNeedsReconnect(false);
      } catch (error) {
        if (!cancelled) {
          setConnectionNeedsReconnect(requiresReconnect(error));
          setMessage(errorMessage(error));
          setMessageRequestId(providerRequestId(error));
        }
      } finally {
        if (!cancelled) setIsLoading(false);
      }
    }

    queueMicrotask(() => { void load(); });
    return () => { cancelled = true; };
  }, [preferredConnectionId, reloadNonce]);

  useEffect(() => {
    if (callbackConnectionId || callbackError) router.replace("/settings/integrations");
  }, [callbackConnectionId, callbackError, router]);

  const installGitHub = async () => {
    if (actionRef.current) return;
    actionRef.current = "install";
    setAction("install");
    setMessage(null);
    setMessageRequestId(null);
    try {
      const request = await plotApiClient.createGitHubInstallationRequest();
      window.location.assign(request.installUrl);
    } catch (error) {
      setMessage(errorMessage(error));
    } finally {
      actionRef.current = null;
      setAction(null);
    }
  };

  const disconnectRepository = async () => {
    if (!connectedRepository?.id || actionRef.current) return;
    actionRef.current = "disconnect";
    setAction("disconnect");
    setMessage(null);
    setMessageRequestId(null);
    try {
      await plotApiClient.disconnectGitHubRepository(connectedRepository.id);
      setConnections((current) => current.map((connection) => connection.id === selectedConnection?.id
        ? {
          ...connection,
          repositories: connection.repositories.map((repository) => repository.id === connectedRepository.id
            ? { ...repository, status: "DISABLED", statusReason: "USER_DISCONNECTED" }
            : repository),
        }
        : connection));
    } catch (error) {
      setMessage(errorMessage(error));
      setMessageRequestId(providerRequestId(error));
    } finally {
      actionRef.current = null;
      setAction(null);
    }
  };

  return (
    <div className="h-full overflow-y-auto bg-[#f4f6f8] px-5 py-8 dark:bg-[#101112] sm:px-8 sm:py-10 lg:px-10">
      <div className="mx-auto max-w-[760px] pb-16">
        <header className="max-w-[720px]">
          <h1 className="font-serif text-[32px] font-normal leading-[1.08] tracking-[-0.025em] text-black/90 dark:text-white/92 sm:text-[36px]">
            Integrations
          </h1>
          <p className="mt-2 max-w-[620px] text-[14px] leading-6 text-black/52 dark:text-white/50">
            Connect the tools that feed Plot with the context behind your product work.
          </p>
        </header>

        <label className="mt-7 flex h-11 max-w-[720px] items-center gap-3 rounded-[10px] border border-black/10 bg-white px-3.5 shadow-[0_1px_2px_rgb(15_23_42_/_0.025)] transition focus-within:border-black/25 focus-within:ring-2 focus-within:ring-black/[0.04] dark:border-white/10 dark:bg-white/[0.045] dark:focus-within:border-white/25">
          <HugeiconsIcon icon={Search01Icon} size={17} color="currentColor" strokeWidth={1.5} className="shrink-0 text-black/35 dark:text-white/35" aria-hidden="true" />
          <span className="sr-only">Search integrations</span>
          <input
            type="search"
            value={integrationQuery}
            onChange={(event) => setIntegrationQuery(event.target.value)}
            placeholder="Search integrations"
            className="min-w-0 flex-1 bg-transparent text-sm text-black/80 outline-none placeholder:text-black/35 dark:text-white/82 dark:placeholder:text-white/35"
          />
          {integrationQuery && (
            <button
              type="button"
              onClick={() => setIntegrationQuery("")}
              aria-label="Clear integration search"
              className="rounded-md p-1 text-black/35 transition hover:bg-black/[0.05] hover:text-black/65 dark:text-white/35 dark:hover:bg-white/10 dark:hover:text-white/70"
            >
              <HugeiconsIcon icon={Cancel01Icon} size={15} color="currentColor" strokeWidth={1.5} aria-hidden="true" />
            </button>
          )}
        </label>

        {githubMatchesQuery && (
          <section className="mt-8" aria-labelledby="essentials-heading">
            <SectionHeading
              id="essentials-heading"
              title="Available integrations"
              description={connectionBadgeStatus === "connected" ? "Configured for this workspace" : "Ready to connect"}
            />

            <div className="mt-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
              <article className="min-h-[160px] rounded-[14px] border border-black/[0.09] bg-white p-4 shadow-[0_1px_2px_rgb(15_23_42_/_0.025)] transition duration-200 hover:-translate-y-0.5 hover:border-black/[0.16] hover:shadow-[0_8px_24px_rgb(15_23_42_/_0.06)] dark:border-white/10 dark:bg-white/[0.045] dark:hover:border-white/20 dark:hover:bg-white/[0.065]">
                <div className="flex items-center gap-3">
                  <BrandIcon brand="github" />
                  <div className="min-w-0 flex-1">
                    <div className="flex min-w-0 items-center gap-2">
                      <h2 className="shrink-0 text-[16px] font-semibold tracking-[-0.01em] text-black/84 dark:text-white/88">GitHub</h2>
                    </div>
                  </div>
                  {isOwner && connectionBadgeStatus === "connected" && connectedRepository?.id && (
                    <button
                      type="button"
                      onClick={() => { void disconnectRepository(); }}
                      disabled={action !== null}
                      aria-label="Disconnect GitHub"
                      title="Disconnect GitHub"
                      className="inline-flex size-8 shrink-0 items-center justify-center rounded-[9px] border border-black/10 text-black/50 transition hover:border-black/20 hover:bg-black/[0.04] hover:text-black/80 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-black/20 disabled:cursor-wait disabled:opacity-50 dark:border-white/12 dark:text-white/55 dark:hover:border-white/25 dark:hover:bg-white/10 dark:hover:text-white/85 dark:focus-visible:ring-white/25"
                    >
                      {action === "disconnect" ? <LoaderCircle className="size-3.5 animate-spin" aria-hidden="true" /> : <HugeiconsIcon icon={Unlink04Icon} size={16} color="currentColor" strokeWidth={1.5} aria-hidden="true" />}
                    </button>
                  )}
                  {isOwner && connectionBadgeStatus !== "connected" && (
                    <button
                      type="button"
                      onClick={() => { void installGitHub(); }}
                      disabled={action !== null}
                      aria-label={connectionBadgeStatus === "attention" ? "Reconnect GitHub" : "Connect GitHub"}
                      title={connectionBadgeStatus === "attention" ? "Reconnect GitHub" : "Connect GitHub"}
                      className="inline-flex size-8 shrink-0 items-center justify-center rounded-[9px] border border-black/10 text-black/50 transition hover:border-black/20 hover:bg-black/[0.04] hover:text-black/80 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-black/20 disabled:cursor-wait disabled:opacity-50 dark:border-white/12 dark:text-white/55 dark:hover:border-white/25 dark:hover:bg-white/10 dark:hover:text-white/85 dark:focus-visible:ring-white/25"
                    >
                      {action === "install" ? <LoaderCircle className="size-3.5 animate-spin" aria-hidden="true" /> : <HugeiconsIcon icon={Link01Icon} size={16} color="currentColor" strokeWidth={1.5} aria-hidden="true" />}
                    </button>
                  )}
                </div>
                <p className="mt-4 text-[13px] leading-5 text-black/50 dark:text-white/50">
                  Connect GitHub to bring source context into your artifacts.
                </p>
                {selectedConnection && (
                  <div className="mt-3 flex min-w-0 items-center gap-2">
                    <ConnectionBadge status={connectionBadgeStatus} />
                  </div>
                )}
                {isLoading && <Loading />}
                {!isLoading && isOwner === false && <NonOwnerState connected={connectionBadgeStatus === "connected" && !connectionNeedsReconnect} />}
                {!isLoading && message && (
                  <StatusMessage
                    message={message}
                    requestId={messageRequestId}
                    onRetry={refresh}
                    onDismiss={() => setMessage(null)}
                  />
                )}
              </article>
            </div>
          </section>
        )}

        {matchingPlannedIntegrations.length > 0 && (
          <section className="mt-11" aria-labelledby="more-integrations-heading">
            <SectionHeading
              id="more-integrations-heading"
              title="More integrations"
              description="Sources we are shaping next"
            />
            <div className="mt-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
              {matchingPlannedIntegrations.map((integration) => (
                <article
                  key={integration.name}
                  className="group min-h-[168px] rounded-[14px] border border-black/[0.08] bg-white/75 p-5 transition duration-200 hover:-translate-y-0.5 hover:border-black/[0.15] hover:bg-white hover:shadow-[0_6px_18px_rgb(15_23_42_/_0.05)] dark:border-white/[0.09] dark:bg-white/[0.035] dark:hover:border-white/15 dark:hover:bg-white/[0.055]"
                >
                  <div className="flex items-start justify-between gap-3">
                    <BrandIcon brand={integration.brand} />
                    <span className="pt-1 text-[10px] font-semibold uppercase tracking-[0.14em] text-black/35 dark:text-white/38">
                      Coming soon
                    </span>
                  </div>
                  <h3 className="mt-5 text-[15px] font-semibold tracking-[-0.01em] text-black/82 dark:text-white/86">
                    {integration.name}
                  </h3>
                  <p className="mt-3 text-[13px] leading-5 text-black/50 dark:text-white/50">
                    {integration.description}
                  </p>
                </article>
              ))}
            </div>
          </section>
        )}

        {!githubMatchesQuery && matchingPlannedIntegrations.length === 0 && (
          <div className="mt-11 rounded-[14px] border border-dashed border-black/10 px-5 py-12 text-center dark:border-white/10">
            <p className="text-sm font-medium text-black/65 dark:text-white/68">No integrations found</p>
            <p className="mt-1 text-sm text-black/42 dark:text-white/42">Try another search term.</p>
          </div>
        )}
      </div>

    </div>
  );
}

function SectionHeading({
  description,
  id,
  title,
}: {
  description: string;
  id: string;
  title: string;
}) {
  return (
    <div className="flex flex-wrap items-end justify-between gap-2">
      <h2 id={id} className="text-[15px] font-semibold tracking-[-0.01em] text-black/78 dark:text-white/82">
        {title}
      </h2>
      <p className="text-[12px] text-black/38 dark:text-white/38">{description}</p>
    </div>
  );
}

function BrandIcon({ brand }: { brand: IntegrationBrand }) {
  return (
    <div className="flex size-11 shrink-0 items-center justify-center rounded-[12px] border border-black/[0.08] bg-white shadow-[0_1px_1px_rgb(15_23_42_/_0.025)] dark:border-white/10">
      {brand === "github" && (
        <svg role="img" aria-label="GitHub" viewBox="0 0 24 24" className="size-[23px] fill-[#181717]">
          <path d="M12 .297c-6.63 0-12 5.373-12 12 0 5.303 3.438 9.8 8.205 11.385.6.113.82-.258.82-.577 0-.285-.01-1.04-.015-2.04-3.338.724-4.042-1.61-4.042-1.61C4.422 18.07 3.633 17.7 3.633 17.7c-1.087-.744.084-.729.084-.729 1.205.084 1.838 1.236 1.838 1.236 1.07 1.835 2.809 1.305 3.495.998.108-.776.417-1.305.76-1.605-2.665-.3-5.466-1.332-5.466-5.93 0-1.31.465-2.38 1.235-3.22-.135-.303-.54-1.523.105-3.176 0 0 1.005-.322 3.3 1.23.96-.267 1.98-.399 3-.405 1.02.006 2.04.138 3 .405 2.28-1.552 3.285-1.23 3.285-1.23.645 1.653.24 2.873.12 3.176.765.84 1.23 1.91 1.23 3.22 0 4.61-2.805 5.625-5.475 5.92.42.36.81 1.096.81 2.22 0 1.606-.015 2.896-.015 3.286 0 .315.21.69.825.57C20.565 22.092 24 17.592 24 12.297c0-6.627-5.373-12-12-12" />
        </svg>
      )}
      {brand === "linear" && (
        <svg role="img" aria-label="Linear" viewBox="0 0 24 24" className="size-[23px] fill-[#5e6ad2]">
          <path d="M2.886 4.18A11.982 11.982 0 0 1 11.99 0C18.624 0 24 5.376 24 12.009c0 3.64-1.62 6.903-4.18 9.105L2.887 4.18ZM1.817 5.626l16.556 16.556c-.524.33-1.075.62-1.65.866L.951 7.277c.247-.575.537-1.126.866-1.65ZM.322 9.163l14.515 14.515c-.71.172-1.443.282-2.195.322L0 11.358a12 12 0 0 1 .322-2.195Zm-.17 4.862 9.823 9.824a12.02 12.02 0 0 1-9.824-9.824Z" />
        </svg>
      )}
      {brand === "slack" && (
        <svg role="img" aria-label="Slack" viewBox="0 0 24 24" className="size-[24px]">
          <path fill="#e01e5a" d="M5.04 15.17a2.52 2.52 0 1 1-2.52-2.52h2.52v2.52Zm1.27 0a2.52 2.52 0 0 1 5.04 0v6.31a2.52 2.52 0 1 1-5.04 0v-6.31Z" />
          <path fill="#36c5f0" d="M8.83 5.04a2.52 2.52 0 1 1 2.52-2.52v2.52H8.83Zm0 1.27a2.52 2.52 0 0 1 0 5.04H2.52a2.52 2.52 0 1 1 0-5.04h6.31Z" />
          <path fill="#2eb67d" d="M18.96 8.83a2.52 2.52 0 1 1 2.52 2.52h-2.52V8.83Zm-1.27 0a2.52 2.52 0 0 1-5.04 0V2.52a2.52 2.52 0 1 1 5.04 0v6.31Z" />
          <path fill="#ecb22e" d="M15.17 18.96a2.52 2.52 0 1 1-2.52 2.52v-2.52h2.52Zm0-1.27a2.52 2.52 0 0 1 0-5.04h6.31a2.52 2.52 0 1 1 0 5.04h-6.31Z" />
        </svg>
      )}
      {brand === "notion" && (
        <svg role="img" aria-label="Notion" viewBox="0 0 24 24" className="size-[23px] fill-black">
          <path d="M4.459 4.208c.746.606 1.026.56 2.428.466l13.215-.793c.28 0 .047-.28-.046-.326L17.86 1.968c-.42-.326-.981-.7-2.055-.607L3.01 2.295c-.466.046-.56.28-.374.466zm.793 3.08v13.904c0 .747.373 1.027 1.214.98l14.523-.84c.841-.046.935-.56.935-1.167V6.354c0-.606-.233-.933-.748-.887l-15.177.887c-.56.047-.747.327-.747.933zm14.337.745c.093.42 0 .84-.42.888l-.7.14v10.264c-.608.327-1.168.514-1.635.514-.748 0-.935-.234-1.495-.933l-4.577-7.186v6.952L12.21 19s0 .84-1.168.84l-3.222.186c-.093-.186 0-.653.327-.746l.84-.233V9.854L7.822 9.76c-.094-.42.14-1.026.793-1.073l3.456-.233 4.764 7.279v-6.44l-1.215-.139c-.093-.514.28-.887.747-.933zM1.936 1.035l13.31-.98c1.634-.14 2.055-.047 3.082.7l4.249 2.986c.7.513.934.653.934 1.213v16.378c0 1.026-.373 1.634-1.68 1.726l-15.458.934c-.98.047-1.448-.093-1.962-.747l-3.129-4.06c-.56-.747-.793-1.306-.793-1.96V2.667c0-.839.374-1.54 1.447-1.632z" />
        </svg>
      )}
      {brand === "figma" && (
        <svg role="img" aria-label="Figma" viewBox="0 0 38 57" className="h-[26px] w-[18px]">
          <path fill="#f24e1e" d="M0 9.5A9.5 9.5 0 0 1 9.5 0H19v19H9.5A9.5 9.5 0 0 1 0 9.5Z" />
          <path fill="#ff7262" d="M19 0h9.5a9.5 9.5 0 1 1 0 19H19V0Z" />
          <path fill="#a259ff" d="M0 28.5A9.5 9.5 0 0 1 9.5 19H19v19H9.5A9.5 9.5 0 0 1 0 28.5Z" />
          <path fill="#1abcfe" d="M19 19h9.5a9.5 9.5 0 1 1 0 19H19V19Z" />
          <path fill="#0acf83" d="M0 47.5A9.5 9.5 0 0 1 9.5 38H19v9.5a9.5 9.5 0 0 1-19 0Z" />
        </svg>
      )}
    </div>
  );
}


function ConnectionBadge({ status }: { status: "connected" | "attention" | "disconnected" }) {
  if (status === "attention") return null;

  const styles = status === "connected"
    ? "bg-emerald-50 text-emerald-700 dark:bg-emerald-500/10 dark:text-emerald-300"
    : "bg-black/[0.05] text-black/45 dark:bg-white/10 dark:text-white/48";
  return (
    <span className={`shrink-0 rounded-full px-2.5 py-1 text-xs font-medium ${styles}`}>
      {status === "connected" ? "Connected" : "Disconnected"}
    </span>
  );
}

function NonOwnerState({ connected }: { connected: boolean }) {
  return (
    <div className="mt-6 rounded-[10px] border border-black/10 bg-black/[0.015] p-4 text-sm dark:border-white/10 dark:bg-white/[0.025]">
      <div className="font-medium text-black/78 dark:text-white/80">
        GitHub is {connected ? "connected" : "not connected"} for this workspace
      </div>
      <p className="mt-1 text-black/52 dark:text-white/52">Workspace owner must connect GitHub.</p>
    </div>
  );
}

function Loading() {
  return (
    <div className="mt-6 flex items-center gap-2 border-t border-black/[0.08] pt-6 text-sm text-black/50 dark:border-white/10 dark:text-white/50">
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
    <div role="alert" className="mt-5 flex items-start gap-3 rounded-[10px] border border-amber-300 bg-amber-50 p-3 text-sm text-amber-900 dark:border-amber-400/30 dark:bg-amber-500/10 dark:text-amber-100">
      <div className="min-w-0 flex-1">
        <p>{message}</p>
        {requestId && (
          <details className="mt-1 text-xs">
            <summary className="cursor-pointer font-medium">Technical details</summary>
            <code>GitHub request {requestId}</code>
          </details>
        )}
      </div>
      <button type="button" onClick={onRetry} className="shrink-0 font-semibold underline underline-offset-2">Retry</button>
      <button type="button" onClick={onDismiss} aria-label="Dismiss message">
        <HugeiconsIcon icon={Cancel01Icon} size={16} aria-hidden="true" />
      </button>
    </div>
  );
}

function callbackMessage(value: string) {
  if (value === "invalid") return "The GitHub installation link expired. Try connecting again.";
  if (value === "unauthorized") return "Only the workspace owner can connect GitHub.";
  if (value === "unavailable") return "GitHub is temporarily unavailable. Try again shortly.";
  return "GitHub could not be connected. Try again.";
}

function errorMessage(error: unknown) {
  if (error instanceof PlotApiError) {
    if (error.code === "GITHUB_NOT_CONFIGURED") return "GitHub is not configured for this environment. Try again after an administrator enables it.";
    if (error.code === "GITHUB_RATE_LIMITED") return "GitHub rate limit reached. Wait a moment, then retry.";
    if (error.code === "GITHUB_NOT_FOUND") return "The previous GitHub installation was replaced or removed. Reconnect GitHub to restore access.";
    if (error.code === "GITHUB_ACCESS_DENIED" || error.code === "CONNECTION_INACTIVE" || error.code === "REPOSITORY_INACTIVE") return "GitHub access was revoked. Reconnect GitHub, then retry.";
    if (error.code === "FORBIDDEN") return "Workspace owner must connect GitHub.";
    if (error.code === "GITHUB_PROVIDER_UNAVAILABLE") return "GitHub is temporarily unavailable. Try again shortly.";
    return "GitHub request failed. Try again.";
  }
  return "Could not update the GitHub integration. Try again.";
}

function providerRequestId(error: unknown) {
  if (!(error instanceof PlotApiError)) return null;
  return error.message.match(/\brequest ([A-Za-z0-9_-]{1,100})\b/)?.[1] ?? null;
}

function requiresReconnect(error: unknown) {
  return error instanceof PlotApiError && new Set([
    "GITHUB_NOT_FOUND",
    "GITHUB_ACCESS_DENIED",
    "CONNECTION_INACTIVE",
    "REPOSITORY_INACTIVE",
  ]).has(error.code);
}
