package org.encog.examples.indicator.avg;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

import org.encog.Encog;
import org.encog.EncogError;
import org.encog.cloud.indicator.basic.BasicIndicator;
import org.encog.cloud.indicator.basic.InstrumentHolder;
import org.encog.cloud.indicator.server.IndicatorLink;
import org.encog.cloud.indicator.server.IndicatorPacket;
import org.encog.ml.MLRegression;
import org.encog.ml.data.MLData;
import org.encog.ml.data.basic.BasicMLData;
import org.encog.util.arrayutil.NormalizationAction;
import org.encog.util.arrayutil.NormalizedField;
import org.encog.util.csv.CSVFormat;

/**
 * This is the actual indicator that will be called remotely from 
 * NinjaTrader.
 */
public class MyInd extends BasicIndicator {

	/**
	 * Holds the data as it is downloaded.
	 */
	private InstrumentHolder holder = new InstrumentHolder();
	
	/**
	 * The number of rows downloaded.
	 */
	private int rowsDownloaded;
	
	/**
	 * The path to store the data files.
	 */
	private File path;
	
	/**
	 * The machine learning method used to predict.
	 */
	private MLRegression method;
	
	/**
	 * Used to normalize the difference between the two SMAs.
	 */
	private final NormalizedField fieldDifference;
	
	/**
	 * Used to normalize the pip profit/loss outcome.
	 */
	private final NormalizedField fieldOutcome;

	/**
	 * Construct the indicator.
	 * @param theMethod The machine learning method to use.
	 * @param thePath The path to use.
	 */
	public MyInd(MLRegression theMethod, File thePath) {
		super(theMethod!=null);
		this.method = theMethod;
		this.path = thePath;
		
		requestData("CLOSE[1]");
		requestData("SMA(10)["+Config.INPUT_WINDOW+"]");
		requestData("SMA(25)["+Config.INPUT_WINDOW+"]");
		
		this.fieldDifference = new NormalizedField(NormalizationAction.Normalize,"diff",Config.DIFF_RANGE,-Config.DIFF_RANGE,1,-1);
		this.fieldOutcome = new NormalizedField(NormalizationAction.Normalize,"out",Config.PIP_RANGE,-Config.PIP_RANGE,1,-1);
	}

	/**
	 * Called to notify the indicator that a bar has been received.
	 * @param packet The packet received.
	 */
	@Override
	public void notifyPacket(IndicatorPacket packet) {
		String security = packet.getArgs()[1];
		long when = Long.parseLong(packet.getArgs()[0]);
		String key = security.toLowerCase();

		if (this.method==null) {
			if (holder.record(when, 2, packet.getArgs())) {
				this.rowsDownloaded++;
			}
		} else {
			MLData input = new BasicMLData(Config.PREDICT_WINDOW);
			
			int fastIndex = 2;
			int slowIndex = fastIndex + Config.INPUT_WINDOW;
			
			for(int i=0;i<3;i++) {
				double fast = CSVFormat.EG_FORMAT.parse(packet.getArgs()[fastIndex+i]);
				double slow = CSVFormat.EG_FORMAT.parse(packet.getArgs()[slowIndex+i]);
				double diff = this.fieldDifference.normalize( (fast - slow)/Config.PIP_SIZE);		
				input.setData(i, this.fieldDifference.normalize(diff) );
			}
						
			MLData result = this.method.compute(input);
			
			double d = result.getData(0);
			d = this.fieldOutcome.deNormalize(d);
			
			String[] args = { 
					"?",	// line 1
					"?",	// line 2
					"?",	// line 3
					CSVFormat.EG_FORMAT.format(d,Encog.DEFAULT_PRECISION), // bar 1
					"?", // bar 2
					"?", // bar 3
					"?", // arrow 1
					"?"}; // arrow 2
			
			this.getLink().writePacket(IndicatorLink.PACKET_IND, args);
		}
	}

	/**
	 * Determine the next file to process.
	 * @return The next file.
	 */
	public File nextFile() {
		int mx = -1;
		File[] list = this.path.listFiles();

		for (File file : list) {
			String fn = file.getName();
			if (fn.startsWith("collected") && fn.endsWith(".csv")) {
				int idx = fn.indexOf(".csv");
				String str = fn.substring(9, idx);
				int n = Integer.parseInt(str);
				mx = Math.max(n, mx);
			}
		}

		return new File(path, "collected" + (mx + 1) + ".csv");
	}

	/**
	 * Write the files that were collected.
	 */
	public void writeCollectedFile() {
		File targetFile = nextFile();

		try {
			FileWriter outFile = new FileWriter(targetFile);
			PrintWriter out = new PrintWriter(outFile);

			// output header
			out.print("\"WHEN\"");
			int index = 0;
			for (String str : this.getDataRequested()) {
				String str2;
				
				// strip off [ if needed
				int ix = str.indexOf('[');
				if (ix != -1) {
					str2 = str.substring(0, ix).trim();
				} else {
					str2 = str;
				}
				
				int c = getDataCount().get(index++);
				if (c <= 1) {
					out.print(",\"" + str2 + "\"");
				} else {
					for (int i = 0; i < c; i++) {						
						out.print(",\"" + str2 + "-b" + i + "\"");
					}
				}
			}
			out.println();

			// output data

			for (Long key : holder.getSorted()) {
				String str = holder.getData().get(key);
				out.println(key + "," + str);
			}

			out.close();
		} catch (IOException ex) {
			throw new EncogError(ex);
		}
	}

	/**
	 * Notify on termination, write the collected file.
	 */
	@Override
	public void notifyTermination() {
		if (this.method==null) {
			writeCollectedFile();
		}
	}

	/**
	 * @return The number of rows downloaded.
	 */
	public int getRowsDownloaded() {
		return rowsDownloaded;
	}
	
	

}
