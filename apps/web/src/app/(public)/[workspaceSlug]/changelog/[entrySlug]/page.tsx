import type { Metadata } from "next";
import { notFound } from "next/navigation";

import { PublicChangelogEntryView } from "@/features/changelog/public-changelog-entry";
import { PublicChangelogLayout } from "@/features/changelog/public-changelog-layout";
import { fetchPublicChangelogEntry } from "@/lib/public-changelog";
import { isPublicChangelogNotFound } from "@/lib/public-changelog-errors";

type PublicChangelogEntryPageProps = {
  params: Promise<{ workspaceSlug: string; entrySlug: string }>;
};

export async function generateMetadata({ params }: PublicChangelogEntryPageProps): Promise<Metadata> {
  const { workspaceSlug, entrySlug } = await params;

  try {
    const entry = await fetchPublicChangelogEntry(workspaceSlug, entrySlug);

    return {
      title: `${entry.title} — ${entry.workspaceName} Changelog`,
      description: `Published update from ${entry.workspaceName}.`,
    };
  } catch (error) {
    if (isPublicChangelogNotFound(error)) {
      return { title: "Changelog not found — Plot" };
    }
    throw error;
  }
}

export default async function PublicChangelogEntryPage({ params }: PublicChangelogEntryPageProps) {
  const { workspaceSlug, entrySlug } = await params;

  let entry;
  try {
    entry = await fetchPublicChangelogEntry(workspaceSlug, entrySlug);
  } catch (error) {
    if (isPublicChangelogNotFound(error)) {
      notFound();
    }
    throw error;
  }

  return (
    <PublicChangelogLayout
      workspaceName={entry.workspaceName}
      workspaceSlug={entry.workspaceSlug}
      logoUrl={entry.logoUrl}
    >
      <PublicChangelogEntryView workspaceSlug={entry.workspaceSlug} entry={entry} />
    </PublicChangelogLayout>
  );
}
