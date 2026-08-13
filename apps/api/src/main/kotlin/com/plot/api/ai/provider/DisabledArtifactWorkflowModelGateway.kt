package com.plot.api.ai.provider

import com.plot.api.artifact.workflow.model.ReviewerOutput
import com.plot.api.artifact.workflow.model.TargetedRewriteOutput
import com.plot.api.artifact.workflow.model.WriterOutput

class DisabledArtifactWorkflowModelGateway : ArtifactWorkflowModelGateway {
	override fun write(request: WriterModelRequest): ModelCallResult<WriterOutput> = notConfigured()

	override fun review(request: ReviewerModelRequest): ModelCallResult<ReviewerOutput> = notConfigured()

	override fun rewrite(request: RewriteModelRequest): ModelCallResult<TargetedRewriteOutput> = notConfigured()

	private fun notConfigured(): Nothing = throw ArtifactWorkflowModelException(
		code = ModelFailureCode.MODEL_NOT_CONFIGURED,
		message = "The artifact workflow model is not configured",
	)
}
