package com.agentframework.common.federation;

import java.util.Map;

/**
 * Outcome of a federated consensus vote.
 *
 * <p>Returned by {@link FederationConsensus#propose} when enough peers have
 * responded (or the timeout has elapsed).</p>
 *
 * @param approved     true if the quorum approved the action
 * @param votesFor     number of servers that voted "approve"
 * @param votesAgainst number of servers that voted "reject"
 * @param serverVotes  detailed vote map: serverId to "approve" or "reject"
 */
public record ConsensusResult(
        boolean approved,
        int votesFor,
        int votesAgainst,
        Map<String, String> serverVotes
) {}
