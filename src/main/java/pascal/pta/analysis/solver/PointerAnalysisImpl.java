package pascal.pta.analysis.solver;

import pascal.callgraph.CallGraph;
import pascal.callgraph.CallKind;
import pascal.callgraph.Edge;
import pascal.pta.analysis.ProgramManager;
import pascal.pta.analysis.context.Context;
import pascal.pta.analysis.context.ContextSelector;
import pascal.pta.analysis.data.CSCallSite;
import pascal.pta.analysis.data.CSMethod;
import pascal.pta.analysis.data.CSObj;
import pascal.pta.analysis.data.CSVariable;
import pascal.pta.analysis.data.DataManager;
import pascal.pta.analysis.data.InstanceField;
import pascal.pta.analysis.data.Pointer;
import pascal.pta.analysis.heap.HeapModel;
import pascal.pta.element.CallSite;
import pascal.pta.element.Method;
import pascal.pta.element.Obj;
import pascal.pta.element.Type;
import pascal.pta.element.Variable;
import pascal.pta.set.PointsToSet;
import pascal.pta.set.PointsToSetFactory;
import pascal.pta.statement.Allocation;
import pascal.pta.statement.Assign;
import pascal.pta.statement.Call;
import pascal.pta.statement.InstanceLoad;
import pascal.pta.statement.InstanceStore;
import pascal.pta.statement.Statement;
import pascal.util.AnalysisException;

import java.util.List;
import java.util.stream.Stream;

public class PointerAnalysisImpl implements PointerAnalysis {

    private ProgramManager programManager;

    private DataManager dataManager;

    private OnFlyCallGraph callGraph;

    private PointerFlowGraph pointerFlowGraph;

    private HeapModel heapModel;

    private ContextSelector contextSelector;

    private PointsToSetFactory setFactory;

    private WorkList workList;

    @Override
    public void setProgramManager(ProgramManager programManager) {
        this.programManager = programManager;
    }

    @Override
    public void setDataManager(DataManager dataManager) {
        this.dataManager = dataManager;
    }

    @Override
    public void setContextSelector(ContextSelector contextSelector) {
        this.contextSelector = contextSelector;
    }

    @Override
    public void setHeapModel(HeapModel heapModel) {
        this.heapModel = heapModel;
    }

    @Override
    public void setPointsToSetFactory(PointsToSetFactory setFactory) {
        this.setFactory = setFactory;
    }

    @Override
    public void solve() {
        initialize();
        analyze();
    }

    @Override
    public CallGraph<CSCallSite, CSMethod> getCallGraph() {
        return callGraph;
    }

    @Override
    public Stream<CSVariable> getVariables() {
        return dataManager.getCSVariables();
    }

    @Override
    public Stream<InstanceField> getInstanceFields() {
        return dataManager.getInstanceFields();
    }

    private void initialize() {
        callGraph = new OnFlyCallGraph(dataManager);
        pointerFlowGraph = new PointerFlowGraph();
        workList = new WorkList();
        for (Method entry : programManager.getEntryMethods()) {
            CSMethod csMethod = dataManager.getCSMethod(
                    contextSelector.getDefaultContext(), entry);
            processNewMethod(csMethod);
            // must be called after processNewMethod()
            callGraph.addEntryMethod(csMethod);
        }
    }

    private void analyze() {
        while (!workList.isEmpty()) {
            while (workList.hasPointerEntries()) {
                WorkList.Entry entry = workList.pollPointerEntry();
                Pointer p = entry.pointer;
                PointsToSet pts = entry.pointsToSet;
                PointsToSet diff = propagate(p, pts);
                if (p instanceof CSVariable) {
                    CSVariable v = (CSVariable) p;
                    processInstanceStore(v, diff);
                    processInstanceLoad(v, diff);
                    processCall(v, diff);
                }
            }
            while (workList.hasCallEdges()) {
                processCallEdge(workList.pollCallEdge());
            }
        }
    }

    private PointsToSet propagate(Pointer pointer, PointsToSet pointsToSet) {
        System.out.println("Propagate " + pointsToSet + " to " + pointer);
        PointsToSet diff = setFactory.makePointsToSet();
        for (CSObj obj : pointsToSet) {
            if (pointer.getPointsToSet().addObject(obj)) {
                diff.addObject(obj);
            }
        }
        if (!diff.isEmpty()) {
            for (PointerFlowEdge edge : pointerFlowGraph.getOutEdgesOf(pointer)) {
                // TODO: check cast here
                Pointer to = edge.getTo();
                workList.addPointerEntry(to, diff);
            }
        }
        return diff;
    }

    private void addPFGEdge(Pointer from, Pointer to, PointerFlowEdge.Kind kind) {
        if (pointerFlowGraph.addEdge(from, to, kind)) {
            if (!from.getPointsToSet().isEmpty()) {
                workList.addPointerEntry(to, from.getPointsToSet());
            }
        }
    }

    private void processInstanceStore(CSVariable baseVar, PointsToSet pts) {
        Context context = baseVar.getContext();
        Variable var = baseVar.getVariable();
        for (InstanceStore store : var.getStores()) {
            CSVariable from = dataManager.getCSVariable(context, store.getFrom());
            for (CSObj baseObj : pts) {
                InstanceField instField = dataManager.getInstanceField(
                        baseObj, store.getField());
                addPFGEdge(from, instField, PointerFlowEdge.Kind.INSTANCE_STORE);
            }
        }
    }

    private void processInstanceLoad(CSVariable baseVar, PointsToSet pts) {
        Context context = baseVar.getContext();
        Variable var = baseVar.getVariable();
        for (InstanceLoad load : var.getLoads()) {
            CSVariable to = dataManager.getCSVariable(context, load.getTo());
            for (CSObj baseObj : pts) {
                InstanceField instField = dataManager.getInstanceField(
                        baseObj, load.getField());
                addPFGEdge(instField, to, PointerFlowEdge.Kind.INSTANCE_LOAD);
            }
        }
    }

    private void processCall(CSVariable recv, PointsToSet pts) {
        Context context = recv.getContext();
        Variable var = recv.getVariable();
        for (Call call : var.getCalls()) {
            CallSite callSite = call.getCallSite();
            for (CSObj recvObj : pts) {
                // resolve callee
                Type type = recvObj.getObject().getType();
                Method callee;
                CallKind callKind;
                if (callSite.isVirtual()) {
                    callee = programManager.resolveVirtualCall(type, callSite.getMethod());
                    callKind = CallKind.VIRTUAL;
                } else if (callSite.isSpecial()){
                    callee = programManager.resolveSpecialCall(
                            callSite, callSite.getContainerMethod());
                    callKind = CallKind.SPECIAL;
                } else {
                    throw new AnalysisException("Unknown CallSite: " + callSite);
                }
                // select context
                CSCallSite csCallSite = dataManager.getCSCallSite(context, callSite);
                Context calleeContext = contextSelector.selectContext(
                        csCallSite, recvObj, callee);
                // build call edge
                CSMethod csCallee = dataManager.getCSMethod(calleeContext, callee);
                workList.addCallEdge(new Edge<>(callKind, csCallSite, csCallee));
                // pass receiver object to *this* variable
                CSVariable thisVar = dataManager.getCSVariable(
                        calleeContext, callee.getThis());
                workList.addPointerEntry(thisVar,
                        setFactory.makePointsToSet(recvObj));
            }
        }
    }

    private void processNewMethod(CSMethod csMethod) {
        if (callGraph.addNewMethod(csMethod)) {
            processAllocations(csMethod);
            addLocalAssignEdgesToPFG(csMethod);
            processStaticCalls(csMethod);
        }
    }

    /**
     * Process allocation statements in given context-sensitive method.
     */
    private void processAllocations(CSMethod csMethod) {
        Context context = csMethod.getContext();
        Method method = csMethod.getMethod();
        for (Statement stmt : method.getStatements()) {
            if (stmt.getKind() == Statement.Kind.ALLOCATION) {
                Allocation alloc = (Allocation) stmt;
                // obtain context-sensitive heap object
                Object allocSite = alloc.getAllocationSite();
                Obj obj = heapModel.getObj(allocSite, alloc.getType(), method);
                Context heapContext = contextSelector.selectHeapContext(csMethod, obj);
                CSObj csObj = dataManager.getCSObj(heapContext, obj);
                // obtain lhs variable
                CSVariable lhs = dataManager.getCSVariable(context, alloc.getVar());
                workList.addPointerEntry(lhs, setFactory.makePointsToSet(csObj));
            }
        }
    }

    /**
     * Add local assign edges of new method to pointer flow graph.
     */
    private void addLocalAssignEdgesToPFG(CSMethod csMethod) {
        Context context = csMethod.getContext();
        Method method = csMethod.getMethod();
        for (Statement stmt : method.getStatements()) {
            if (stmt instanceof Assign) {
                Assign assign = (Assign) stmt;
                CSVariable from = dataManager.getCSVariable(context, assign.getFrom());
                CSVariable to = dataManager.getCSVariable(context, assign.getTo());
                addPFGEdge(from, to, PointerFlowEdge.Kind.LOCAL_ASSIGN);
            }
        }
    }

    /**
     * Process static calls in given context-sensitive method.
     */
    private void processStaticCalls(CSMethod csMethod) {
        Context context = csMethod.getContext();
        Method method = csMethod.getMethod();
        for (Statement stmt : method.getStatements()) {
            if (stmt instanceof Call) {
                CallSite callSite = ((Call) stmt).getCallSite();
                if (callSite.isStatic()) {
                    Method callee = callSite.getMethod();
                    CSCallSite csCallSite = dataManager.getCSCallSite(context, callSite);
                    Context calleeCtx = contextSelector.selectContext(csCallSite, callee);
                    CSMethod csCallee = dataManager.getCSMethod(calleeCtx, callee);
                    Edge<CSCallSite, CSMethod> edge =
                            new Edge<>(CallKind.STATIC, csCallSite, csCallee);
                    workList.addCallEdge(edge);
                }
            }
        }
    }

    /**
     * Process the call edges in work list.
     */
    private void processCallEdge(Edge<CSCallSite, CSMethod> edge) {
        if (!callGraph.containsEdge(edge)) {
            callGraph.addEdge(edge);
            CSMethod csCallee = edge.getCallee();
            processNewMethod(csCallee);
            Context callerCtx = edge.getCallSite().getContext();
            CallSite callSite = edge.getCallSite().getCallSite();
            Context calleeCtx = csCallee.getContext();
            Method callee = csCallee.getMethod();
            // pass arguments to parameters
            List<Variable> args = callSite.getArguments();
            List<Variable> params = callee.getParameters();
            for (int i = 0; i < args.size(); ++i) {
                CSVariable arg = dataManager.getCSVariable(callerCtx, args.get(i));
                CSVariable param = dataManager.getCSVariable(calleeCtx, params.get(i));
                addPFGEdge(arg, param, PointerFlowEdge.Kind.PARAMETER_PASSING);
            }
            // pass results to LHS variable
            if (callSite.getCall().getLHS() != null) {
                CSVariable lhs = dataManager.getCSVariable(
                        callerCtx, callSite.getCall().getLHS());
                for (Variable ret : callee.getReturnVariables()) {
                    CSVariable csRet = dataManager.getCSVariable(calleeCtx, ret);
                    addPFGEdge(csRet, lhs, PointerFlowEdge.Kind.RETURN);
                }
            }
        }
    }
}