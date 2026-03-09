package com.agentframework.common.federation;

import java.util.List;

/**
 * SPI for routing tasks to the optimal server in a federated cluster.
 *
 * <p>When multiple servers participate in plan execution, the router decides
 * which server should handle each task based on worker availability, load,
 * and capability. The routing decision happens before dispatch; the actual
 * task payload is sent via {@link #dispatchRemote}.</p>
 *
 * <p>Routing criteria (for future implementations):</p>
 * <ul>
 *   <li>Worker type availability — does the target server have the required worker?</li>
 *   <li>Load balancing — distribute tasks across servers to avoid hotspots</li>
 *   <li>Data locality — prefer the server that already has relevant context</li>
 *   <li>GP prediction — route to the server whose worker profile has highest predicted reward</li>
 * </ul>
 *
 * <p>No concrete implementation exists yet. This interface establishes the
 * contract for future federation providers.</p>
 */
public interface FederationTaskRouter {

    /**
     * Determines which server should execute the given task.
     *
     * @param task             descriptor of the task to route
     * @param availableServers servers currently active in the federation
     * @return the selected target server
     */
    ServerIdentity route(FederatedTaskDescriptor task, List<ServerIdentity> availableServers);

    /**
     * Dispatches a task to a remote server for execution.
     *
     * <p>The full task payload (not just the descriptor) is transmitted.
     * The remote server acknowledges receipt and assumes responsibility
     * for dispatch to its local worker pool.</p>
     *
     * @param task   descriptor of the task to dispatch
     * @param target the server that will execute the task
     */
    void dispatchRemote(FederatedTaskDescriptor task, ServerIdentity target);
}
