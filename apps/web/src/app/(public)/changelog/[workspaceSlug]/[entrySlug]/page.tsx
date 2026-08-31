import type { Metadata } from "next";
import { notFound } from "next/navigation";

import { PublicChangelogEntryView } from "@/features/changelog/public-changelog-entry";
import { PublicChangelogLayout } from "@/features/changelog/public-changelog-layout";
import {
  fetchPublicChangelog,
  fetchPublicChangelogEntry,
} from "@/lib/public-changelog";
import { isPublicChangelogNotFound } from "@/lib/public-changelog-errors";

type PublicChangelogEntryPageProps = {
  params: Promise<{ workspaceSlug: string; entrySlug: string }>;
};

export async function generateMetadata({ params }: PublicChangelogEntryPageProps): Promise<Metadata> {
  const { workspaceSlug, entrySlug } = await params;

  try {
    const [changelog, entry] = await Promise.all([
      fetchPublicChangelog(workspaceSlug),
      fetchPublicChangelogEntry(workspaceSlug, entrySlug),
    ]);

    return {
      title: `${entry.title} — ${changelog.workspaceName} Changelog`,
      description: `Published update from ${changelog.workspaceName}.`,
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

  try {
    const [changelog, entry] = await Promise.all([
      fetchPublicChangelog(workspaceSlug),
      fetchPublicChangelogEntry(workspaceSlug, entrySlug),
    ]);

    return (
      <PublicChangelogLayout
        workspaceName={changelog.workspaceName}
        workspaceSlug={changelog.workspaceSlug}
        logoUrl={changelog.logoUrl}
      >
        <PublicChangelogEntryView workspaceSlug={changelog.workspaceSlug} entry={entry} />
      </PublicChangelogLayout>
    );
  } catch (error) {
    if (isPublicChangelogNotFound(error)) {
      notFound();
    }
    throw error;
  }
}
