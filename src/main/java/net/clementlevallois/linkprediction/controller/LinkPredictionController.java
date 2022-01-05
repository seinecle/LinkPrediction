/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package net.clementlevallois.linkprediction.controller;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.stream.Collectors;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gephi.graph.api.Column;
import org.gephi.graph.api.Edge;
import org.gephi.graph.api.Graph;
import org.gephi.graph.api.GraphController;
import org.gephi.graph.api.GraphFactory;
import org.gephi.graph.api.GraphModel;
import org.gephi.graph.api.Node;
import org.gephi.graph.api.Table;
import org.gephi.io.exporter.api.ExportController;
import org.gephi.io.exporter.spi.Exporter;
import org.gephi.io.importer.api.Container;
import org.gephi.io.importer.api.ContainerUnloader;
import org.gephi.io.importer.api.ImportController;
import org.gephi.io.importer.plugin.file.ImporterGEXF;
import org.gephi.io.importer.spi.FileImporter;
import org.gephi.io.processor.plugin.DefaultProcessor;
import org.gephi.project.api.ProjectController;
import org.gephi.project.api.Workspace;
import org.openide.util.Lookup;

/**
 *
 * @author LEVALLOIS
 */
public class LinkPredictionController {

    public final int ITERATION_LIMIT_DEFAULT = 1;
    public final int EDGES = 1;
    /**
     * Long runtime threshold, warning if value is reached
     */
    public final long RUNTIME_THRESHOLD = 1000000;

    // Columns for data labour
    protected Column colLastPrediction;
    protected Column colAddedInRun;
    protected Column colLastCalculatedValue;

    // Graph to calculate predictions on
    protected Graph graph;
    // Big o complexity of algorithm
    protected Complexity complexity;
    // Queue of predictions, highest first
    protected PriorityQueue<LinkPredictionProbability> queue = new PriorityQueue<>(Collections.reverseOrder());
    // Prediction probabilities
    protected List<LinkPredictionProbability> probabilities = new ArrayList<>();
    // Last predicted edge
    protected Edge lastPrediction;
    // Highest prediction
    protected LinkPredictionProbability highestPrediction;

    private List<LinkPredictionProbability> topPredictions = new ArrayList();

    private Container container;

    private Workspace workspace;

    public GraphModel graphModel;

    private String resultFileName;

    // Console Logger
    private Logger consoleLogger = LogManager.getLogger(LinkPredictionStatistics.class);

    public static void main(String[] args) throws FileNotFoundException {
        File file = new File("miserables_result.gexf");
        InputStream targetStream = new FileInputStream(file);
        new LinkPredictionController().runPrediction(targetStream, 3, "_abc");
    }

    public void runPrediction(InputStream is, int nbOfPredictions, String uniqueId) {
        ProjectController projectController = Lookup.getDefault().lookup(ProjectController.class);
        GraphController graphController = Lookup.getDefault().lookup(GraphController.class);
        ImportController importController = Lookup.getDefault().lookup(ImportController.class);
        projectController.newProject();

        FileImporter fi = new ImporterGEXF();
        container = importController.importFile(is, fi);
        container.closeLoader();
        DefaultProcessor processor = new DefaultProcessor();
        processor.setWorkspace(projectController.getCurrentWorkspace());
        processor.setContainers(new ContainerUnloader[]{container.getUnloader()});
        processor.process();
        graphModel = graphController.getGraphModel();
        Table edgeTable = graphModel.getEdgeTable();
        initializeColumns(edgeTable);

        // result file name
        resultFileName = "result_prediction_" + uniqueId + ".gexf";

        // Get graph factory
        consoleLogger.debug("Get factory");
        graph = graphModel.getGraph();
        GraphFactory factory = graphModel.factory();

        // Lock graph for writes
        consoleLogger.debug("Lock graph");
        graph.writeLock();

        if (isInitialExecution()) {
            // Iterate on all nodes for first execution
            calculateAll(factory);

        } else {
            // Only change affected node for subsequent iterations
            recalculateAffected(factory);
        }

        // Add highest predicted edge to graph
        addNHighestPredictedEdgeToGraph(factory, getAlgorithmName(), nbOfPredictions);

        // Unlock graph
        consoleLogger.debug("Unlock graph");
        graph.writeUnlock();
        workspace = projectController.getCurrentWorkspace();

        ExportController ec = Lookup.getDefault().lookup(ExportController.class);
        Exporter exporterGexf = ec.getExporter("gexf");
        exporterGexf.setWorkspace(workspace);
        try {
            ec.exportFile(new File(resultFileName), exporterGexf);
        } catch (IOException ex) {
            ex.printStackTrace();
            return;
        }

    }

    protected void addNHighestPredictedEdgeToGraph(GraphFactory factory, String algorithm, int n) {
        // Get highest predicted value
        for (int i = 0; i < n; i++) {
            highestPrediction = getAndRemoveHighestPrediction();
            consoleLogger.log(Level.DEBUG, () -> "Highest predicted value is " + highestPrediction);

            final Edge max;
            if (highestPrediction != null) {
                topPredictions.add(highestPrediction);
                // Create corresponding edge
                max = factory.newEdge(highestPrediction.getNodeSource(), highestPrediction.getNodeTarget(), false);

                // Add edge to graph
                int iteration = getNextIteration(graph, algorithm);
                max.setAttribute(colAddedInRun, iteration);
                max.setAttribute(colLastPrediction, algorithm);
                max.setAttribute(colLastCalculatedValue, highestPrediction.getPredictionValue());
                consoleLogger.log(Level.DEBUG, () -> "Add highest predicted edge: " + max);

                graph.addEdge(max);
                lastPrediction = max;
            }
        }
    }

    /**
     * Gets the edge to add, with the highest calculated prediction.
     *
     * @return Edge to add to the network
     */
    public LinkPredictionProbability getAndRemoveHighestPrediction() {
        return queue.poll();
    }

    /**
     * Gets the name of the respective algorithm.
     *
     * @return Algorithm name
     */
    public String getAlgorithmName() {
        return "preferential attachement";
    }

    public void initializeColumns(Table edgeTable) {
        // Column containing info about last prediction algorithm
        colLastPrediction = edgeTable.getColumn(LinkPredictionColumn.LP_ALGORITHM.getName());
        consoleLogger.debug("Initialize column " + LinkPredictionColumn.LP_ALGORITHM.getName());
        if (colLastPrediction == null) {
            colLastPrediction = edgeTable.addColumn(LinkPredictionColumn.LP_ALGORITHM.getName(), "Chosen Link Prediction Algorithm", String.class, "");
        }

        // Column containing info about iteration in which edge was added
        colAddedInRun = edgeTable.getColumn(LinkPredictionColumn.ADDED_IN_RUN.getName());
        consoleLogger.debug("Initialize column " + LinkPredictionColumn.ADDED_IN_RUN.getName());
        if (colAddedInRun == null) {
            colAddedInRun = edgeTable.addColumn(LinkPredictionColumn.ADDED_IN_RUN.getName(), "Added in Run", Integer.class, 0);
        }

        // Column containing info about the calculated value
        colLastCalculatedValue = edgeTable.getColumn(LinkPredictionColumn.LAST_VALUE.getName());
        consoleLogger.debug("Initialize column " + LinkPredictionColumn.LAST_VALUE.getName());
        if (colLastCalculatedValue == null) {
            colLastCalculatedValue = edgeTable.addColumn(LinkPredictionColumn.LAST_VALUE.getName(), "Last Link Prediction Value", Integer.class, 0);
        }
    }

    /**
     * Gets the number of the highest added iteration per algorithm.
     *
     * @param graph Graph currently working on
     * @param algorithm Used algorithm
     * @return Number of highest iteration
     */
    public int getMaxIteration(Graph graph, String algorithm) {
        consoleLogger.debug("Get current max iteration");
        return Arrays.asList(graph.getEdges().toArray()).stream()
                .filter(edge -> edge.getAttribute(colLastPrediction).toString().equals(algorithm))
                .map(edge -> (int) edge.getAttribute(colAddedInRun)).max(Comparator.comparing(Integer::valueOf))
                .orElse(0);
    }

    /**
     * Generates a report after link prediction calculation has finished.
     *
     * @return HTML report
     */
    public String getReport() {
        //This is the HTML report shown when execution ends.
        //One could add a distribution histogram for instance
        return "<HTML> <BODY> <h1>Link Prediction</h1> " + "<hr>"
                + "<br> No global results to show. Check Data Laboratory for results" + "<br />" + "</BODY></HTML>";
    }

    /**
     * Gets the column "last prediction".
     *
     * @return Column "last prediction"
     */
    public Column getColLastPrediction() {
        return colLastPrediction;
    }

    /**
     * Gets the columns "added in run".
     *
     * @return Column "added in run"
     */
    public Column getColAddedInRun() {
        return colAddedInRun;
    }

    /**
     * Gets the column "last calculated value".
     *
     * @return Column "last calculated value"
     */
    public Column getColLastCalculatedValue() {
        return colLastCalculatedValue;
    }

    /**
     * Gets the complexity of the algorithm.
     *
     * @return Algorithms complexity
     */
    public Complexity getComplexity() {
        return complexity;
    }

    /**
     * Gets the edge to add, with the highest calculated prediction.
     *
     * @return Edge to add to the network
     */
    public LinkPredictionProbability getHighestPrediction() {
        return queue.peek();
    }

    /**
     * Gets the number of the next iteration per algorithm.
     *
     * @param graph Graph currently working on
     * @param algorithm Used algorithm
     * @return Number of next iteration
     */
    public int getNextIteration(Graph graph, String algorithm) {
        int lastIteration = Arrays.asList(graph.getEdges().toArray()).stream()
                .filter(edge -> edge.getAttribute(colLastPrediction).toString().equals(algorithm))
                .map(edge -> (int) edge.getAttribute(colAddedInRun)).max(Comparator.comparing(Integer::valueOf))
                .orElse(0);
        consoleLogger.log(Level.DEBUG, () -> "Number of last iteration: " + lastIteration);

        return lastIteration + 1;
    }

    /**
     * Verifies if two statistics are equal.
     *
     * @param o Other statistic
     * @return Evaluation result
     */
    @Override
    public boolean equals(Object o) {
        if (o != null) {
            return o.getClass() == this.getClass();
        } else {
            return false;
        }
    }

    /**
     * Generates hash code out of class.
     *
     * @return Hash code
     */
    @Override
    public int hashCode() {
        return this.getClass().hashCode();
    }

    /**
     * Recalculates the link prediction probability for neighbours of affected
     * nodes.
     *
     * @param factory Factory to create new edges
     * @param a Center node
     */
    protected void recalculateProbability(GraphFactory factory, Node a) {
        consoleLogger.debug("Recalculate probability for affected nodes");
        // Get neighbours of a
        List<Node> aNeighbours = getNeighbours(a);

        // Get edges and remove
        // self from potential neighbours
        List<Node> nodesB = new ArrayList<>(Arrays.asList(graph.getNodes().toArray()));
        nodesB.remove(a);

        // Iterate over other nodes
        // that could become new neighbours
        for (Node b : nodesB) {

            // Update temporary saved values
            // if edge does not exist
            if (isNewEdge(a, b, "preferential attachement")) {
                consoleLogger.log(Level.DEBUG, () -> "Calculation for edge new between " + a.getId() + " and " + b.getId());
                List<Node> bNeighbours = getNeighbours(b);
                int totalNeighboursCount = aNeighbours.size() * bNeighbours.size();

                // Update saved and calculated values
                consoleLogger.log(Level.DEBUG, () -> "Update value to " + totalNeighboursCount);
                updateCalculatedValue(factory, a, b, totalNeighboursCount);
            }
        }
    }

    /**
     * Checks if undirected edge between node a and b exists.
     *
     * @param a Source/target node
     * @param b Source/target node
     * @return Whether edge already does not exist already
     */
    protected boolean isNewEdge(Node a, Node b, String algorithm) {
        // Get edges between a and b
        consoleLogger.log(Level.DEBUG, () -> "Check if edge exists already");
        // FIXME graph.getEdges returns always null
        List<Edge> existingEdges = GraphUtils.getEdges(graph, a, b);

        // Retain only edges with respective algorithm
        existingEdges.removeIf(
                edge -> !edge.getAttribute(colLastPrediction).equals(algorithm) && !edge.getAttribute(colLastPrediction)
                .equals(""));

        // Count number of edges
        long numberOfExistingEdges = existingEdges.size();
        consoleLogger.log(Level.DEBUG, () -> "Size of existing edges: " + numberOfExistingEdges);

        return numberOfExistingEdges == 0;
    }

    /**
     * Saves calculated values in temporary data structures for further
     * iterations.
     *
     * @param factory Factory to create new edge
     * @param a Node a
     * @param b Node b
     * @param value Calculated prediction value
     */
    protected void saveCalculatedValue(GraphFactory factory, Node a, Node b, int value) {
        // Create new edge
        Edge newEdge = factory.newEdge(a, b, false);
        newEdge.setAttribute(colLastCalculatedValue, value);
        consoleLogger.log(Level.DEBUG, () -> "Save edge: " + a.getLabel() + ", " + b.getLabel() + ", " + value);

        // Add edge to temporary helper data structures
        LinkPredictionProbability predictionProbability = new LinkPredictionProbability(newEdge.getSource(),
                newEdge.getTarget(), value);
        queue.add(predictionProbability);
        probabilities.add(predictionProbability);
    }

    /**
     * Updates calculated values in temporary data structures for further
     * iterations.
     *
     * @param factory Factory to create new edge
     * @param a Node a
     * @param b Node b
     * @param value Calculated prediction value
     */
    protected void updateCalculatedValue(GraphFactory factory, Node a, Node b, int value) {
        // Get calculated prediction probability
        LinkPredictionProbability predictionProbability = getPredictionProbability(a, b);

        // Create edge with new calculated value
        Edge newEdge = factory.newEdge(a, b, false);
        newEdge.setAttribute(colLastCalculatedValue, value);
        consoleLogger.log(Level.DEBUG,
                () -> "Temporarily add new edge: " + a.getLabel() + ", " + b.getLabel() + ", " + value);

        if (predictionProbability != null) {
            // Update values
            queue.remove(predictionProbability);
            predictionProbability.setPredictionValue(value);
            queue.add(predictionProbability);
        } else {
            // Create probability object
            LinkPredictionProbability newProbability = new LinkPredictionProbability(newEdge.getSource(),
                    newEdge.getTarget(), value);
            probabilities.add(newProbability);
            queue.add(newProbability);
        }
    }

    /**
     * Gets already calculated prediction probability.
     *
     * @param a Node a
     * @param b Node b
     * @return Probability of edge between node a and b
     */
    protected LinkPredictionProbability getPredictionProbability(Node a, Node b) {
        consoleLogger.debug("Get prediction probability");

        // Loop through calculated values
        LinkPredictionProbability predictionProbability = null;
        for (LinkPredictionProbability p : probabilities) {
            if ((p.getNodeSource().equals(a) && p.getNodeTarget().equals(b)) || (p.getNodeSource().equals(b)
                    && p.getNodeTarget().equals(a))) {
                consoleLogger.log(Level.DEBUG, () -> "Probability is " + p);
                predictionProbability = p;
            }
        }

        return predictionProbability;
    }

    /**
     * Iterates over all nodes twice to initially calculate prediction values.
     *
     * @param factory Factory to create new edges
     */
    protected void calculateAll(GraphFactory factory) {
        // Iterate on all nodes for first execution
        consoleLogger.debug("Initial calculation");
        ArrayList<Node> nodesA = new ArrayList<Node>(Arrays.asList(graph.getNodes().toArray()));
        ArrayList<Node> nodesB = new ArrayList<Node>(Arrays.asList(graph.getNodes().toArray()));

        for (Node a : nodesA) {
            consoleLogger.log(Level.DEBUG, () -> "Calculation for node " + a.getId());

            // Remove self from neighbours
            nodesB.remove(a);

            // Get neighbours of a
            ArrayList<Node> aNeighbours = getNeighbours(a);

            // Calculate preferential attachment
            for (Node b : nodesB) {
                // Get neighbours of b
                consoleLogger.log(Level.DEBUG, () -> "Calculation for node " + b.getId());
                ArrayList<Node> bNeighbours = getNeighbours(b);

                // Calculate prediction value
                int totalNeighboursCount = aNeighbours.size() * bNeighbours.size();
                consoleLogger.log(Level.DEBUG, () -> "Total neighbours product: " + totalNeighboursCount);

                // Temporary save calculated
                // value if edge does not exist
                if (isNewEdge(a, b, "preferential attachement")) {
                    saveCalculatedValue(factory, a, b, totalNeighboursCount);
                }
            }
        }

    }

    ;

    /**
     * Recalculates link prediction probability for nodes, affected by last prediction.
     *
     * @param factory Factory to create new edge
     */
    protected void recalculateAffected(GraphFactory factory) {
        // Recalculate only affected nodes
        consoleLogger.debug("Subsequent calculation");
        // Remove last added element from queue
        highestPrediction = getHighestPrediction();
        queue.remove(highestPrediction);

        // Recalculate for affected nodes
        Node a = lastPrediction.getSource();
        Node b = lastPrediction.getTarget();
        recalculateProbability(factory, a);
        recalculateProbability(factory, b);
    }

    /**
     * Adds highest predicted edge to graph.
     *
     * @param factory Factory to create edge
     * @param algorithm
     */
    protected void addHighestPredictedEdgeToGraph(GraphFactory factory, String algorithm) {
        // Get highest predicted value
        highestPrediction = getHighestPrediction();
        consoleLogger.log(Level.DEBUG, () -> "Highest predicted value is " + highestPrediction);

        final Edge max;
        if (highestPrediction != null) {
            // Create corresponding edge
            max = factory.newEdge(highestPrediction.getNodeSource(), highestPrediction.getNodeTarget(), false);

            // Add edge to graph
            int iteration = getNextIteration(graph, algorithm);
            max.setAttribute(colAddedInRun, iteration);
            max.setAttribute(colLastPrediction, algorithm);
            max.setAttribute(colLastCalculatedValue, highestPrediction.getPredictionValue());
            consoleLogger.log(Level.DEBUG, () -> "Add highest predicted edge: " + max);

            graph.addEdge(max);
            lastPrediction = max;
        }
    }

    /**
     * Checks if execute is executed the first time.
     *
     * @return If initial execution
     */
    protected boolean isInitialExecution() {
        return queue.isEmpty() && lastPrediction == null;
    }

    /**
     * Retrieve neighbours for node a from graph
     *
     * @param n Node for which neighbours will be searched
     * @return Neighbours, that were added by algorithm or already have been
     * there initially
     */
    protected ArrayList<Node> getNeighbours(Node n) {
        consoleLogger.debug("Get relevant neighbours");

        // Get all neighbours
        ArrayList<Node> neighbours = new ArrayList<>(
                Arrays.asList(graph.getNeighbors(n).toArray()).stream()
                        .distinct()
                        .collect(Collectors.toList()));

        // Filter neighbours with edges from
        // same algorithm or that initially existed
        return new ArrayList<>(neighbours.stream()
                .filter(r -> GraphUtils.getEdges(graph, n, r).stream().filter(e -> e.getAttribute(colLastPrediction).equals(getAlgorithmName()) || e
                .getAttribute(colLastPrediction).equals("")).count() > 0)
                .distinct()
                .collect(Collectors.toList()));

    }

    /**
     * Statistic class used to store link prediction information for handling
     * priority queue.
     */
    public class LinkPredictionProbability implements Comparable<LinkPredictionProbability> {

        private Node nodeSource;
        private Node nodeTarget;
        private Integer predictionValue;

        public LinkPredictionProbability(Node nodeSource, Node nodeTarget, int predictionValue) {
            this.nodeSource = nodeSource;
            this.nodeTarget = nodeTarget;
            this.predictionValue = predictionValue;
        }

        /**
         * Compares two probability objects.
         *
         * @param o Compared instances
         * @return Comparison value based on prediction value
         */
        @Override
        public int compareTo(LinkPredictionProbability o) {
            return this.getPredictionValue().compareTo(o.getPredictionValue());
        }

        /**
         * Verifies if two prediction probabilities are equal.
         *
         * @param o Other statistic
         * @return Evaluation result
         */
        @Override
        public boolean equals(Object o) {
            if (o instanceof LinkPredictionProbability) {
                // Object is from same class
                LinkPredictionProbability probability = (LinkPredictionProbability) o;
                // Object has same source node and target node
                return (this.getNodeSource().equals(probability.getNodeSource()) && this.getNodeTarget()
                        .equals(probability.getNodeTarget())) || (this.getNodeTarget().equals(probability.getNodeSource()) && this.getNodeSource()
                        .equals(probability.getNodeTarget()));
            } else {
                return false;
            }
        }

        /**
         * Generates hash code out of prediction value.
         *
         * @return Hash code
         */
        @Override
        public int hashCode() {
            return this.getPredictionValue().hashCode();
        }

        /**
         * Gets predicted probability.
         *
         * @return Link prediction value
         */
        public Integer getPredictionValue() {
            return predictionValue;
        }

        /**
         * Sets predicted probability.
         *
         * @param predictionValue Link prediction value
         */
        public void setPredictionValue(int predictionValue) {
            this.predictionValue = predictionValue;
        }

        /**
         * Gets source node.
         *
         * @return Source node
         */
        public Node getNodeSource() {
            return nodeSource;
        }

        /**
         * Gets target node.
         *
         * @return target node
         */
        public Node getNodeTarget() {
            return nodeTarget;
        }
    }

    public List<LinkPredictionProbability> getTopPredictions() {
        return topPredictions;
    }

    public void setTopPredictions(List<LinkPredictionProbability> topPredictions) {
        this.topPredictions = topPredictions;
    }

    public String getResultFileName() {
        return resultFileName;
    }

    public void setResultFileName(String resultFileName) {
        this.resultFileName = resultFileName;
    }

    public Container getContainer() {
        return container;
    }

    public void setContainer(Container container) {
        this.container = container;
    }

    public Workspace getWorkspace() {
        return workspace;
    }

    public void setWorkspace(Workspace workspace) {
        this.workspace = workspace;
    }

}
