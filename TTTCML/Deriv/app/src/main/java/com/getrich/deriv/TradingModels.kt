package com.getrich.deriv

enum class TradingModel { CLASSIC, FOUR_AGENT }

data class AgentDecision(
    val scannerApproved: Boolean,
    val analystApproved: Boolean,
    val riskApproved: Boolean,
    val executionAuthorized: Boolean,
    val reason: String
)

/** Deterministic Model 2 approval chain. It does not submit orders. */
class FourAgentPipeline {
    fun evaluate(
        freshMarketData: Boolean,
        opportunityScore: Int,
        accountBalance: Double,
        proposedStake: Double,
        payoutPercent: Double,
        autoTradingEnabled: Boolean
    ): AgentDecision {
        if (!freshMarketData)
            return AgentDecision(false, false, false, false, "Agent 1: market data is stale.")
        if (opportunityScore < 80)
            return AgentDecision(true, false, false, false, "Agent 2: opportunity score is below 80.")
        val riskApproved = accountBalance > 0.0 && proposedStake > 0.0 &&
            proposedStake <= accountBalance * 0.03 && payoutPercent >= 70.0
        if (!riskApproved)
            return AgentDecision(true, true, false, false, "Agent 3: risk, stake or payout check failed.")
        return AgentDecision(true, true, true, autoTradingEnabled,
            if (autoTradingEnabled) "Agents 1–3 approved; Agent 4 may proceed to final broker validation."
            else "Agents 1–3 approved; Auto Trader is off, so no order will be sent.")
    }
}
