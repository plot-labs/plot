import type { GitHubConnection } from "@/lib/api-client";

export type GitHubConnectionStatus = "none" | "connected" | "attention" | "disconnected";

export function connectionIsRemoved(connection: GitHubConnection) {
  return connection.status === "DISABLED" || connection.statusReason === "INSTALLATION_UNINSTALLED";
}

export function githubConnectionStatus(connections: GitHubConnection[], needsReconnect = false): GitHubConnectionStatus {
  const current = connections.filter((connection) => !connectionIsRemoved(connection));
  if (connections.length === 0) return "none";
  if (current.length === 0) return "disconnected";
  if (needsReconnect || current.some((connection) => connection.status !== "ACTIVE")) return "attention";
  return "connected";
}

export function followedRepositoryCount(connections: GitHubConnection[]) {
  return connections
    .filter((connection) => !connectionIsRemoved(connection))
    .reduce((total, connection) => total + connection.repositories.filter((repository) => repository.status === "ACTIVE").length, 0);
}
