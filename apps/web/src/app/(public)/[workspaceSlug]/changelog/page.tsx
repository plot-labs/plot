import type { Metadata } from "next";
import { notFound } from "next/navigation";

import { PublicChangelogLayout } from "@/features/changelog/public-changelog-layout";
import { PublicChangelogList } from "@/features/changelog/public-changelog-list";
import { fetchPublicChangelog } from "@/lib/public-changelog";
import { isPublicChangelogNotFound } from "@/lib/public-changelog-errors";

type PublicChangelogPageProps = {
  params: Promise<{ workspaceSlug: string }>;
};

export async function generateMetadata({ params }: PublicChangelogPageProps): Promise<Metadata> {
  const { workspaceSlug } = await params;

  try {
    const changelog = await fetchPublicChangelog(workspaceSlug);
    return {
      title: `${changelog.workspaceName} Changelog — Plot`,
      description: `Published updates from ${changelog.workspaceName}.`,
    };
  } catch (error) {
    if (isPublicChangelogNotFound(error)) {
      return { title: "Changelog not found — Plot" };
    }
    throw error;
  }
}

export default async function PublicChangelogPage({ params }: PublicChangelogPageProps) {
  const { workspaceSlug } = await params;

  let changelog;
  try {
    changelog = await fetchPublicChangelog(workspaceSlug);
  } catch (error) {
    if (isPublicChangelogNotFound(error)) {
      notFound();
    }
    throw error;
  }

  return (
    <PublicChangelogLayout
      workspaceName={changelog.workspaceName}
      workspaceSlug={changelog.workspaceSlug}
      logoUrl={changelog.logoUrl}
    >
      <PublicChangelogList workspaceSlug={changelog.workspaceSlug} entries={changelog.entries} />
    </PublicChangelogLayout>
  );
}
