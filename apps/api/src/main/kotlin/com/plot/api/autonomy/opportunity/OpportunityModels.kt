package com.plot.api.autonomy.opportunity

import com.plot.api.autonomy.assessment.AssessmentDisposition
import java.time.Instant
import java.util.UUID
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@ConfigurationProperties("plot.autonomy.opportunity")
data class OpportunityProperties(
    val dailyAssessmentLimit: Int = 100,
    val activeGoalLimit: Int = 20,
    val assessmentLeaseSeconds: Long = 180,
    val maxAssessmentAttempts: Int = 3,
) { init { require(dailyAssessmentLimit > 0 && activeGoalLimit > 0 && assessmentLeaseSeconds > 0 && maxAssessmentAttempts > 0) } }

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OpportunityProperties::class)
class OpportunityConfiguration

class OpportunityException(val code: String, val recoverable: Boolean = false) : RuntimeException(code)

data class MissionRecord(val id: UUID, val workspaceId: UUID, val sourceScopeId: UUID, val state: String)
data class GoalRecord(val id: UUID, val opportunityId: UUID, val state: String, val agentRunId: UUID?, val fingerprint: String)
data class OpportunityRecord(
    val id: UUID, val workspaceId: UUID, val sourceScopeId: UUID, val subjectKey: String,
    val title: String, val disposition: AssessmentDisposition, val reason: String,
    val fingerprint: String?, val dismissed: Boolean, val version: Long,
    val evidenceIds: List<String>, val missingFacts: List<String>, val lastErrorCode: String?, val goalId: UUID?,
    val goalState: String?, val agentRunId: UUID?, val updatedAt: Instant, val chatId: UUID? = null,
)
