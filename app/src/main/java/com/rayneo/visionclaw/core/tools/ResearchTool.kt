package com.rayneo.visionclaw.core.tools

import com.rayneo.visionclaw.core.network.ResearchRouter

class ResearchTool(
    private val researchRouter: ResearchRouter
) : AiTapTool {

    override val name: String = "research_topic"

    override suspend fun execute(args: Map<String, String>): Result<String> {
        val topic = args["topic"]?.trim().orEmpty()
        if (topic.isBlank()) {
            return Result.failure(IllegalArgumentException("Missing topic for research request."))
        }

        return when (val result = researchRouter.research(topic)) {
            is ResearchRouter.ResearchResult.Success ->
                Result.success(ResearchRouter.formatForDisplay(result))
            is ResearchRouter.ResearchResult.ApiKeyMissing ->
                Result.failure(IllegalStateException("Research provider API key missing."))
            is ResearchRouter.ResearchResult.Error ->
                Result.failure(IllegalStateException(result.message))
        }
    }
}
