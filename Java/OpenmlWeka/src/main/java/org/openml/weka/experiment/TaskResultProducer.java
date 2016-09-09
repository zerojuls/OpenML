package org.openml.weka.experiment;

import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.lang3.ArrayUtils;
import org.openml.apiconnector.algorithms.Conversion;
import org.openml.apiconnector.algorithms.TaskInformation;
import org.openml.apiconnector.io.OpenmlConnector;
import org.openml.apiconnector.models.Metric;
import org.openml.apiconnector.models.MetricScore;
import org.openml.apiconnector.xml.DataSetDescription;
import org.openml.apiconnector.xml.RunList;
import org.openml.apiconnector.xml.RunList.Run;
import org.openml.apiconnector.xml.Task;
import org.openml.apiconnector.xml.Task.Input.Data_set;
import org.openml.apiconnector.xml.Task.Input.Estimation_procedure;
import org.openml.weka.algorithm.InstancesHelper;
import org.openml.weka.algorithm.OptimizationTrace;
import org.openml.weka.algorithm.WekaAlgorithm;
import org.openml.weka.algorithm.OptimizationTrace.Quadlet;
import org.openml.weka.algorithm.WekaConfig;

import weka.core.AttributeStats;
import weka.core.Instances;
import weka.core.UnsupportedAttributeTypeException;
import weka.experiment.CrossValidationResultProducer;
import weka.experiment.OutputZipper;

public class TaskResultProducer extends CrossValidationResultProducer {

	private static UserMeasures[] USER_MEASURES = {
			new UserMeasures("predictive_accuracy", "openml.evaluation.predictive_accuracy(1.0)", "Percent_correct", .01),
			new UserMeasures("kappa", "openml.evaluation.kappa(1.0)", "Kappa_statistic"),
			new UserMeasures("root_mean_squared_error", "openml.evaluation.root_mean_squared_error(1.0)", "Root_mean_squared_error"),
			new UserMeasures("root_relative_squared_error", "openml.evaluation.root_relative_squared_error(1.0)", "Root_relative_squared_error", .01),
			new UserMeasures("usercpu_time_millis_training", "openml.evaluation.usercpu_time_millis_training(1.0)", "UserCPU_Time_millis_training"),
			new UserMeasures("usercpu_time_millis_testing", "openml.evaluation.usercpu_time_millis_testing(1.0)", "UserCPU_Time_millis_testing"),
			new UserMeasures("usercpu_time_millis", "openml.evaluation.usercpu_time_millis(1.0)", "UserCPU_Time_millis"), };

	private static final long serialVersionUID = 1L;

	private static final String FOLDS_FILE_TRAIN = "TRAIN";
	private static final String FOLDS_FILE_TEST = "TEST";

	public static final String TASK_FIELD_NAME = "OpenML_Task_id";
	public static final String SAMPLE_FIELD_NAME = "Sample";
	
	/** The task to be run */
	protected Task m_Task;
	protected boolean regressionTask;
	protected boolean missingLabels;

	/** Instances file with splits in it **/
	protected Instances m_Splits;

	/** Number of samples, if applicable **/
	protected int m_NumSamples = 1; // default to 1

	/** Current task information string **/
	protected String currentTaskRepresentation = "";

	protected OpenmlConnector apiconnector;
	protected WekaConfig openmlconfig;

	public TaskResultProducer(OpenmlConnector apiconnector, WekaConfig openmlconfig) {
		super();
		this.m_SplitEvaluator = new OpenmlClassificationSplitEvaluator();
		this.apiconnector = apiconnector;
		this.openmlconfig = openmlconfig;
	}

	public void setTask(Task t) throws Exception {
		m_Task = t;

		regressionTask = t.getTask_type_id() == 2;

		if (regressionTask) {
			throw new Exception("OpenML Plugin Exception: Regression tasks currently not supported. Aborting.");
		}

		/*
		 * if( regressionTask && !( m_SplitEvaluator instanceof
		 * OpenmlRegressionSplitEvaluator ) ) { m_SplitEvaluator = new
		 * OpenmlRegressionSplitEvaluator(); } else if( !( m_SplitEvaluator
		 * instanceof OpenmlClassificationSplitEvaluator ) ) { m_SplitEvaluator
		 * = new OpenmlClassificationSplitEvaluator(); }
		 */

		Data_set ds = TaskInformation.getSourceData(m_Task);
		Estimation_procedure ep = TaskInformation.getEstimationProcedure(m_Task);

		DataSetDescription dsd = ds.getDataSetDescription(apiconnector);
		m_Instances = new Instances(new FileReader(dsd.getDataset(apiconnector.getApiKey())));

		InstancesHelper.setTargetAttribute(m_Instances, ds.getTarget_feature());
		int targetAttributeIndex = InstancesHelper.getAttributeIndex(m_Instances, ds.getTarget_feature());
		AttributeStats targetStats = m_Instances.attributeStats(targetAttributeIndex);
		missingLabels = targetStats.missingCount > 0;

		// remove attributes that may not be used.
		if (dsd.getIgnore_attribute() != null) {
			for (String ignoreAttr : dsd.getIgnore_attribute()) {
				String attName = ignoreAttr;
				Integer attIdx = m_Instances.attribute(ignoreAttr).index();
				Conversion.log("OK", "Remove Attribte", "Removing attribute " + attName + " (1-based index: " + attIdx + ")");
				m_Instances.deleteAttributeAt(attIdx);
			}
		}

		if (dsd.getRow_id_attribute() != null) {
			String attName = dsd.getRow_id_attribute();
			Integer attIdx = m_Instances.attribute(dsd.getRow_id_attribute()).index();
			Conversion.log("OK", "Remove Attribte", "Removing attribute " + attName + " (1-based index: " + attIdx + ")");
			m_Instances.deleteAttributeAt(attIdx);
		}

		m_Splits = new Instances(new FileReader(ep.getDataSplits(m_Task.getTask_id())));

		currentTaskRepresentation = "Task " + m_Task.getTask_id() + " (" + TaskInformation.getSourceData(m_Task).getDataSetDescription(apiconnector).getName() + ")";

		m_NumFolds = 1;
		m_NumSamples = 1;
		try {
			m_NumFolds = TaskInformation.getNumberOfFolds(t);
		} catch (Exception e) {
		}
		try {
			m_NumSamples = TaskInformation.getNumberOfSamples(t);
		} catch (Exception e) {
		}
	}

	@Override
	public void setInstances(Instances m_Instances) {
		throw new RuntimeException("TaskResultProducer Exception: function setInstances may not be invoked. Use setTask instead. ");
	}

	/**
	 * Gets the names of each of the columns produced for a single run. This
	 * method should really be static.
	 * 
	 * @return an array containing the name of each column
	 */
	@Override
	public String[] getKeyNames() {
		// TODO: also add keys of splitevaluator again, for analyse panel
		// String[] keyNames = m_SplitEvaluator.getKeyNames();
		// Add in the names of our extra key fields
		String[] newKeyNames = new String[/* keyNames.length + */5];
		newKeyNames[0] = DATASET_FIELD_NAME;
		newKeyNames[1] = RUN_FIELD_NAME;
		newKeyNames[2] = FOLD_FIELD_NAME;
		newKeyNames[3] = SAMPLE_FIELD_NAME;
		newKeyNames[4] = TASK_FIELD_NAME;
		// System.arraycopy(keyNames, 0, newKeyNames, 5, keyNames.length);
		return newKeyNames;
	}

	/**
	 * Gets the data types of each of the columns produced for a single run.
	 * This method should really be static.
	 * 
	 * @return an array containing objects of the type of each column. The
	 *         objects should be Strings, or Doubles.
	 */
	@Override
	public Object[] getKeyTypes() {

		// TODO: also add keys of splitevaluator again, for analyse panel
		// Object[] keyTypes = m_SplitEvaluator.getKeyTypes();
		// Add in the types of our extra fields
		Object[] newKeyTypes = new String[/* keyTypes.length + */5];
		newKeyTypes[0] = new String();
		newKeyTypes[1] = new String();
		newKeyTypes[2] = new String();
		newKeyTypes[3] = new String();
		newKeyTypes[4] = new String();
		// System.arraycopy(keyTypes, 0, newKeyTypes, 5, keyTypes.length);
		return newKeyTypes;
	}

	/**
	 * Gets the names of each of the columns produced for a single run. This
	 * method should really be static.
	 * 
	 * @return an array containing the name of each column
	 */
	@Override
	public String[] getResultNames() {

		// TODO: also add keys of splitevaluator again, for analyse panel
		// String[] resultNames = m_SplitEvaluator.getResultNames();
		// Add in the names of our extra Result fields
		String[] newResultNames = new String[/* resultNames.length + */1];
		newResultNames[0] = TIMESTAMP_FIELD_NAME;
		// System.arraycopy(resultNames, 0, newResultNames, 1,
		// resultNames.length);
		return newResultNames;
	}

	/**
	 * Gets the data types of each of the columns produced for a single run.
	 * This method should really be static.
	 * 
	 * 
	 * @return an array containing objects of the type of each column. The
	 *         objects should be Strings, or Doubles.
	 */
	@Override
	public Object[] getResultTypes() {

		// TODO: also add keys of splitevaluator again, for analyse panel
		// Object[] resultTypes = m_SplitEvaluator.getResultTypes();
		// Add in the types of our extra Result fields
		Object[] newResultTypes = new Object[/* resultTypes.length + */1];
		newResultTypes[0] = new Double(0);
		// System.arraycopy(resultTypes, 0, newResultTypes, 1,
		// resultTypes.length);
		return newResultTypes;
	}

	public void doFullRun() throws Exception {
		Conversion.log("OK", "Total Model", "Started building a model over the full dataset. ");
		OpenmlSplitEvaluator tse = ((OpenmlSplitEvaluator) m_SplitEvaluator);

		Map<String, Object> splitEvaluatorResults = WekaAlgorithm.splitEvaluatorToMap(tse, tse.getResult(m_Instances, m_Instances));
		
		if (m_ResultListener instanceof TaskResultListener) {
			((TaskResultListener) m_ResultListener).acceptFullModel(m_Task, m_Instances, tse.getClassifier(), (String) tse.getKey()[1], splitEvaluatorResults, tse);
		}
		
		Conversion.log("OK", "Total Model", "Done building full dataset model. ");
	}

	@Override
	public void doRun(int run) throws Exception {
		boolean useSamples = false;
		int attTypeIndex = m_Splits.attribute("type").index();
		int attRowidIndex = m_Splits.attribute("rowid").index();
		int attFoldIndex = m_Splits.attribute("fold").index();
		int attRepeatIndex = m_Splits.attribute("repeat").index();
		int attSampleIndex = -1;
		OpenmlSplitEvaluator tse = ((OpenmlSplitEvaluator) m_SplitEvaluator);
		String currentRunRepresentation = currentTaskRepresentation + " with " + (String) tse.getKey()[0] + " - Repeat " + (run - 1);
		Conversion.log("OK", "Attribtes", "Attributes available: " + InstancesHelper.getAttributes(m_Instances));
		Conversion.log("OK", "Class", "Class attribute: " + m_Instances.classAttribute());

		if (m_Splits.attribute("sample") != null) {
			attSampleIndex = m_Splits.attribute("sample").index();
			useSamples = true;
		}

		if (getRawOutput()) {
			if (m_ZipDest == null) {
				m_ZipDest = new OutputZipper(m_OutputFile);
			}
		}

		if (m_Instances == null) {
			throw new Exception("No Instances set");
		}

		if (m_Task == null) {
			throw new Exception("No task set");
		}

		if (openmlconfig.getSkipRunPerformedTest() == false) {
			Integer setupId = WekaAlgorithm.getSetupId((String) tse.getKey()[0], (String) tse.getKey()[1], apiconnector);

			if (setupId != null) {
				List<Integer> taskIds = new ArrayList<Integer>();
				taskIds.add(m_Task.getTask_id());
				List<Integer> setupIds = new ArrayList<Integer>();
				setupIds.add(setupId);

				try {
					RunList rl = apiconnector.runList(taskIds, setupIds);

					if (rl.getRuns().length > 0) {
						List<Integer> runIds = new ArrayList<Integer>();
						for (Run r : rl.getRuns()) {
							runIds.add(r.getRun_id());
						}

						Conversion.log("INFO", "Skip", "Skipping run, already available. Run ids: " + runIds);
						return;
					}
				} catch (Exception e) {}
			}
		}

		// creating all empty copies for each fold
		Instances[][] trainingSets = new Instances[m_NumFolds][m_NumSamples];
		Instances[][] testSets = new Instances[m_NumFolds][m_NumSamples];
		Map<Integer, Integer[]> rowids = new HashMap<Integer, Integer[]>();

		for (int i = 0; i < m_NumFolds; ++i) {
			for (int j = 0; j < m_NumSamples; ++j) {
				trainingSets[i][j] = new Instances(m_Instances, 0, 0);
				testSets[i][j] = new Instances(m_Instances, 0, 0);

			}
		}

		// This is the part where we sample all train and test sets.
		// We should not measure time for doing so, since this is OpenML
		// overhead.
		Conversion.log("INFO", "Splits", "Obtaining folds for " + currentRunRepresentation);
		for (int i = 0; i < m_Splits.numInstances(); ++i) {
			int repeat = (int) m_Splits.instance(i).value(attRepeatIndex);
			if (repeat == run - 1) { // 1-based/0-based correction
				int fold = (int) m_Splits.instance(i).value(attFoldIndex);
				int sample = (attSampleIndex < 0) ? 0 : (int) m_Splits.instance(i).value(attSampleIndex);
				String type = m_Splits.attribute(attTypeIndex).value((int) m_Splits.instance(i).value(attTypeIndex));
				int rowid = (int) m_Splits.instance(i).value(attRowidIndex);

				if (type.equals(FOLDS_FILE_TRAIN)) {
					trainingSets[fold][sample].add(m_Instances.instance(rowid));
				} else if (type.equals(FOLDS_FILE_TEST)) {
					testSets[fold][sample].add(m_Instances.instance(rowid));
					if (rowids.containsKey(foldSampleIdx(fold, sample))) {
						rowids.put(foldSampleIdx(fold, sample), ArrayUtils.addAll(rowids.get(foldSampleIdx(fold, sample)), rowid));
					} else {
						Integer[] n = { rowid };
						rowids.put(foldSampleIdx(fold, sample), n);
					}
				}
			}
		} // END OF OPENML SAMPLING
		Conversion.log("INFO", "Splits", "Folds obtained ");

		int repeat = run - 1; // 0/1 based
		for (int fold = 0; fold < m_NumFolds; fold++) {
			for (int sample = 0; sample < m_NumSamples; ++sample) {
				// Add in some fields to the key like run and fold number, data
				// set, name
				String currentFoldRepresentation = "fold " + fold + (useSamples ? ", sample " + sample : "");
				Conversion.log("INFO", "Perform Run", "Started on performing " + currentRunRepresentation + ", " + currentFoldRepresentation);

				try {
					Map<Metric, MetricScore> userMeasures = new HashMap<Metric, MetricScore>();

					Object[] seResults = tse.getResult(trainingSets[fold][sample], testSets[fold][sample]);
					Object[] results = new Object[seResults.length + 1];
					results[0] = getTimestamp();
					System.arraycopy(seResults, 0, results, 1, seResults.length);

					Map<String, Object> splitEvaluatorResults = WekaAlgorithm.splitEvaluatorToMap(tse, seResults);
					List<Quadlet<String, Double, List<Entry<String, Object>>, Boolean>> trace = null;
					try {
						trace = OptimizationTrace.extractTrace(tse.getClassifier());
					} catch (Exception e) {
						Conversion.log("OK", "Trace", e.getMessage());
					}

					// just adding an additional measure: UserCPU_Time_millis
					// (total training time + test time)
					if (splitEvaluatorResults.containsKey("UserCPU_Time_millis_training") && splitEvaluatorResults.containsKey("UserCPU_Time_millis_testing")) {
						double traintime = (Double) splitEvaluatorResults.get("UserCPU_Time_millis_training");
						double testtime = (Double) splitEvaluatorResults.get("UserCPU_Time_millis_testing");
						splitEvaluatorResults.put("UserCPU_Time_millis", traintime + testtime);
					}

					if (missingLabels == false) {
						for (UserMeasures um : USER_MEASURES) {
							if (splitEvaluatorResults.containsKey(um.wekaFunctionName)) {
								userMeasures.put(new Metric(um.openmlFunctionName, um.openmlImplementationName), new MetricScore(
										((Double) splitEvaluatorResults.get(um.wekaFunctionName)) * um.factor, testSets[fold][sample].size()));
							}
						}
					}
					
					boolean modelFullDataset = openmlconfig.getModelFullDataset();
					if (m_ResultListener instanceof TaskResultListener) {
						((TaskResultListener) m_ResultListener).acceptResultsForSending(m_Task, m_Instances, repeat, fold, (useSamples ? sample : null),
								tse.getClassifier(), (String) tse.getKey()[1], rowids.get(foldSampleIdx(fold, sample)), tse.recentPredictions(), userMeasures,
								trace, modelFullDataset);
					}
				} catch (UnsupportedAttributeTypeException ex) {
					// Save the train and test data sets for debugging purposes?
					Conversion.log("ERROR", "Perform Run", "Unable to finish " + currentRunRepresentation + ", " + currentFoldRepresentation + " with "
							+ tse.getClassifier().getClass().getName() + ": " + ex.getMessage());
					if (m_ResultListener instanceof TaskResultListener) {
						((TaskResultListener) m_ResultListener).acceptErrorResult(m_Task, m_Instances, tse.getClassifier(), ex.getMessage(), (String) tse.getKey()[1]);
					}
				}
			}
		}
	}

	private int foldSampleIdx(int fold, int sample) {
		return fold * m_NumSamples + sample;
	}

	private static class UserMeasures {
		private final String openmlFunctionName;
		private final String openmlImplementationName;
		private final String wekaFunctionName;
		private final double factor;

		private UserMeasures(String openmlFunctionName, String openmlImplementationName, String wekaFunctionName, double factor) {
			this.openmlFunctionName = openmlFunctionName;
			this.openmlImplementationName = openmlImplementationName;
			this.wekaFunctionName = wekaFunctionName;
			this.factor = factor;
		}

		private UserMeasures(String openmlFunctionName, String openmlImplementationName, String wekaFunctionName) {
			this(openmlFunctionName, openmlImplementationName, wekaFunctionName, 1.0D);
		}
	}
}
