package bamboo.dataflow.analysis;

/**
 *
 * @param <Domain> Type for lattice values
 * @param <Node> Type for nodes of control-flow graph
 */
public interface DataFlowAnalysis<Domain, Node> {

    /**
     * Returns whether the analysis is forward.
     */
    boolean isForward();

    /**
     * Returns initial in-flow for entry node.
     */
    Domain getEntryInitialFlow(Node entry);

    /**
     * Returns initial out-flow value for other nodes.
     */
    Domain newInitialFlow();

    /**
     * Meet operation for two lattice values.
     */
    Domain meet(Domain v1, Domain v2);

    /**
     * Transfer function for the analysis.
     * The function transfer data-flow from in to out, and return whether
     * the out flow has been changed by the transfer.
     */
    boolean transfer(Node node, Domain in, Domain out);

}