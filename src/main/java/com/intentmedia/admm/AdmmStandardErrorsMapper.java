package com.intentmedia.admm;

import org.apache.commons.math.linear.OpenMapRealMatrix;
import org.apache.commons.math.linear.RealMatrix;
import org.apache.commons.math.linear.SparseRealMatrix;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.*;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.intentmedia.admm.AdmmIterationHelper.*;

public class AdmmStandardErrorsMapper extends MapReduceBase
        implements Mapper<LongWritable, Text, IntWritable, Text> {

    private static final IntWritable ZERO = new IntWritable(0);
    private static final Logger LOG = Logger.getLogger(AdmmIterationMapper.class.getName());
    private static final float DEFAULT_REGULARIZATION_FACTOR = 0.000001f;

    private int iteration;
    private FileSystem fs;
    private Map<String, String> splitToParameters;
    private Set<Integer> columnsToExclude;

    private boolean addIntercept;
    private String previousIntermediateOutputLocation;
    private Path previousIntermediateOutputLocationPath;

    @Override
    public void configure(JobConf job) {
        iteration = Integer.parseInt(job.get("iteration.number"));
        String columnsToExcludeString = job.get("columns.to.exclude");
        columnsToExclude = getColumnsToExclude(columnsToExcludeString);
        addIntercept = job.getBoolean("add.intercept", false);
        previousIntermediateOutputLocation = job.get("previous.intermediate.output.location");
        previousIntermediateOutputLocationPath = new Path(previousIntermediateOutputLocation);

        try {
            fs = FileSystem.get(job);
        } catch (IOException e) {
            LOG.log(Level.FINE, e.toString());
        }

        splitToParameters = getSplitParameters();
    }

    protected Map<String, String> getSplitParameters() {
        return readParametersFromHdfs(fs, previousIntermediateOutputLocationPath, iteration);
    }

    @Override
    public void map(LongWritable key, Text value, OutputCollector<IntWritable, Text> output, Reporter reporter)
            throws IOException {
        FileSplit split = (FileSplit) reporter.getInputSplit();
        String splitId = key.get() + "@" + split.getPath();
        splitId = removeIpFromHdfsFileName(splitId);

        double[][] inputSplitData = createMatrixFromDataString(value.toString(), columnsToExclude, addIntercept);
        AdmmMapperContext mapperContext = assembleMapperContextFromCache(inputSplitData, splitId);

        AdmmStandardErrorsReducerContext reducerContext = getReducerContext(mapperContext);
        output.collect(ZERO, new Text(splitId + "::" + admmStandardErrorReducerContextToJson(reducerContext)));
    }

    private AdmmStandardErrorsReducerContext getReducerContext(AdmmMapperContext mapperContext) {
        double[] zFinal = mapperContext.getZInitial();
        double[][] aMatrix = mapperContext.getA();
        int numRows = aMatrix.length;
        int numFeatures = aMatrix[0].length;
        double[][] xwxMatrix = new double[numFeatures][numFeatures];
        double[] rowMultipliers = getRowMultipliers(aMatrix, zFinal);

        SparseRealMatrix xtW = new OpenMapRealMatrix(numFeatures, numRows);
        SparseRealMatrix x = new OpenMapRealMatrix(numRows, numFeatures);
        for (int row = 0; row < numRows; row++) {
            for (int col = 0; col < numFeatures; col++) {
                if (aMatrix[row][col] != 0) {
                    x.setEntry(row, col, aMatrix[row][col]);
                    xtW.setEntry(col, row, aMatrix[row][col] * rowMultipliers[row]);
                }
            }
        }

        RealMatrix xtWX = xtW.multiply(x);

        for (int row = 0; row < numFeatures; row++) {
            for (int col = 0; col < numFeatures; col++) {
                xwxMatrix[row][col] = xtWX.getEntry(row, col);
            }
        }

        return new AdmmStandardErrorsReducerContext(xwxMatrix, mapperContext.getLambdaValue(), numRows);
    }

    private double[] getRowMultipliers(double[][] aMatrix, double[] zFinal) {
        double[] rowMultipliers = new double[aMatrix.length];
        for (int row = 0; row < rowMultipliers.length; row++) {
            double rowProbability = getPredictedProbability(aMatrix, zFinal, row);
            rowMultipliers[row] = rowProbability * (1 - rowProbability);
        }
        return rowMultipliers;
    }

    private double getPredictedProbability(double[][] aMatrix, double[] zFinal, int row) {
        double[] features = aMatrix[row];
        double dotProduct = 0;
        for (int i = 0; i < features.length; i++) {
            dotProduct += features[i] * zFinal[i];
        }
        return Math.exp(dotProduct) / (1 + Math.exp(dotProduct));
    }

    private AdmmMapperContext assembleMapperContextFromCache(double[][] inputSplitData, String splitId) throws IOException {
        if (splitToParameters.containsKey(splitId)) {
            AdmmMapperContext preContext = jsonToAdmmMapperContext(splitToParameters.get(splitId));
            return new AdmmMapperContext(inputSplitData,
                    preContext.getUInitial(),
                    preContext.getXInitial(),
                    preContext.getZInitial(),
                    preContext.getRho(),
                    preContext.getLambdaValue(),
                    preContext.getPrimalObjectiveValue(),
                    preContext.getRNorm(),
                    preContext.getSNorm());
        } else {
            LOG.log(Level.FINE, String.format("Key not found. Split ID: %s Split Map: %s", splitId, splitToParameters.toString()));
            throw new IOException(String.format("Key not found.  Split ID: %s Split Map: %s", splitId, splitToParameters.toString()));
        }
    }
}
