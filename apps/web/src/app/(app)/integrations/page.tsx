import { redirect } from "next/navigation";

type LegacyIntegrationsPageProps = {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
};

export default async function LegacyIntegrationsPage({ searchParams }: LegacyIntegrationsPageProps) {
  const incoming = await searchParams;
  const outgoing = new URLSearchParams();

  for (const key of ["githubConnection", "githubError"] as const) {
    const value = incoming[key];
    if (typeof value === "string") outgoing.set(key, value);
  }

  const query = outgoing.toString();
  redirect(`/settings/integrations${query ? `?${query}` : ""}`);
}
