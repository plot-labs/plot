package com.plot.api.github

import java.util.UUID

/** Customer-value decision runs after range/evidence capture and before any artifact admission. */
interface GitHubReleasePreparationGate {
    fun shouldPrepare(request: GitHubReleaseDraftRequest, context: GitHubReleaseSourceContext, evidence: GitHubReleaseEvidence): Boolean
    fun admitted(request: GitHubReleaseDraftRequest, agentRunId: UUID) {}
}
