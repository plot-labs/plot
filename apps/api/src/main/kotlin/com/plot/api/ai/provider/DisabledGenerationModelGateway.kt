package com.plot.api.ai.provider

import com.plot.api.generation.model.ReviewerOutput
import com.plot.api.generation.model.TargetedRewriteOutput
import com.plot.api.generation.model.WriterOutput

class DisabledGenerationModelGateway : GenerationModelGateway {
	override fun write(request: WriterModelRequest): ModelCallResult<WriterOutput> = notConfigured()

	override fun review(request: ReviewerModelRequest): ModelCallResult<ReviewerOutput> = notConfigured()

	override fun rewrite(request: RewriteModelRequest): ModelCallResult<TargetedRewriteOutput> = notConfigured()

	private fun notConfigured(): Nothing = throw GenerationModelException(
		code = ModelFailureCode.MODEL_NOT_CONFIGURED,
		message = "The generation model is not configured",
	)
}
