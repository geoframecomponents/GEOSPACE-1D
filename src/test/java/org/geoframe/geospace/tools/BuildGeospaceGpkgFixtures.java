package org.geoframe.geospace.tools;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

import org.geoframe.geoet.io.GeoetInputsHandler;
import org.geoframe.whetgeo1d.io.Whetgeo1DInputsHandler;
import org.hortonmachine.dbs.compat.ADb;
import org.hortonmachine.dbs.compat.EDb;
import org.hortonmachine.dbs.compat.IHMPreparedStatement;
import org.hortonmachine.dbs.utils.SqlName;
import org.hortonmachine.gears.io.timeseries.OmsTimeSeriesReader;
import org.hortonmachine.gears.io.vectorreader.OmsVectorReader;
import org.hortonmachine.gears.libs.modules.HMRaster;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.feature.FeatureIterator;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.joda.time.DateTime;
import ucar.ma2.Array;
import ucar.nc2.NetcdfFile;
import ucar.nc2.Variable;

/**
 * Reusable tool that builds a GEOSPACE-1D coupled-scenario test
 * input GeoPackage from the real, already-committed project data: the shared
 * real grid {@code data/Grid_NetCDF/Grid_GEOSPACE_test.nc} (WHETGEO-1D's
 * {@code grid_geometry}/{@code swrc_parameters}/{@code initial_condition}/
 * {@code pseudo_cell} tables via {@link Whetgeo1DInputsHandler#createDbFromCsv})
 * plus that scenario's real site BC/met CSVs. Both schemas
 * share one physical gpkg file - their table names don't collide. Used to
 * author the checked-in fixtures under {@code src/test/resources/input/gpkg/};
 * not needed at test-run time (the input handlers just read the result).
 */
public class BuildGeospaceGpkgFixtures {

	public static void main(String[] args) throws Exception {
		buildSpikeIIPriestleyTaylor();
	}

	/**
	 * {@code SpikeIIPriestleyTaylor.gpkg}: same grid, site data, date range and
	 * rootDepth as the old {@code testGEOSPACE.TestGEOSPACE_PriestleyTaylor}.
	 */
	private static void buildSpikeIIPriestleyTaylor() throws Exception {
		String outPath = "src/test/resources/input/gpkg/SpikeIIPriestleyTaylor.gpkg";
		String netcdfGrid = "data/Grid_NetCDF/Grid_GEOSPACE_test.nc";
		String siteDir = "data/SpikeII/";

		String startDate = "2018-05-10 00:00";
		String endDate = "2018-06-29 02:00";
		double rootDepth = -2;

		File csvFolder = Files.createTempDirectory("geospace-fixture-").toFile();
		double[] rootDensityIC = writeGridCsvs(netcdfGrid, csvFolder);
		Files.copy(new File(siteDir, "Prec_Irrig_Height_hourly.csv").toPath(),
				new File(csvFolder, "timeseries_topBC.csv").toPath(), StandardCopyOption.REPLACE_EXISTING);
		Files.copy(new File(siteDir, "SpikeII_0.csv").toPath(), new File(csvFolder, "timeseries_bottomBC.csv").toPath(),
				StandardCopyOption.REPLACE_EXISTING);

		File out = new File(outPath);
		if (out.exists()) {
			out.delete();
		}
		try (ADb db = EDb.GEOPACKAGE.getDb()) {
			db.open(outPath);
			Whetgeo1DInputsHandler.createDbFromCsv(csvFolder.getAbsolutePath(), db);

			// NOTE: GeoetInputsHandler.PARAM_ALPHA ("alpha") is one shared key, but the
			// old test used two *different* alphas under that same field name on two
			// different solvers: PriestleyTaylorActualETSolverMain.alpha = 1.26 (a
			// physical PT constant) and JarvisNetRadiationStressFactorSolverMain.alpha =
			// 0.005 (an unrelated stress-shape coefficient). They can't both live under
			// GeoetInputsHandler.PARAM_ALPHA in one parameters row, so this fixture uses
			// two distinct custom keys instead.
			String paramPriestleyTaylorAlpha = "priestleyTaylorAlpha";
			String paramJarvisAlpha = "jarvisAlpha";

			Map<String, Object> parameters = new LinkedHashMap<>();
			parameters.put(GeoetInputsHandler.PARAM_START_DATE, startDate);
			parameters.put(GeoetInputsHandler.PARAM_END_DATE, endDate);
			parameters.put(GeoetInputsHandler.PARAM_TIME_STEP_MINUTES, 60);
			parameters.put(GeoetInputsHandler.PARAM_ROOTS_DEPTH, rootDepth);
			parameters.put(GeoetInputsHandler.PARAM_ELEVATION,
					readElevationAtCentroid(siteDir + "centroid.shp", siteDir + "DemSpikeIIcut.tif"));
			parameters.put(paramPriestleyTaylorAlpha, 1.26);
			parameters.put(GeoetInputsHandler.PARAM_SOIL_FLUX_PARAMETER_DAY, 0.35);
			parameters.put(GeoetInputsHandler.PARAM_SOIL_FLUX_PARAMETER_NIGHT, 0.75);
			parameters.put(GeoetInputsHandler.PARAM_USE_RADIATION_STRESS, 0);
			parameters.put(GeoetInputsHandler.PARAM_USE_TEMPERATURE_STRESS, 0);
			parameters.put(GeoetInputsHandler.PARAM_USE_VDP_STRESS, 0);
			parameters.put(GeoetInputsHandler.PARAM_USE_WATER_STRESS, 1);
			parameters.put(paramJarvisAlpha, 0.005);
			parameters.put(GeoetInputsHandler.PARAM_THETA, 0.9);
			parameters.put(GeoetInputsHandler.PARAM_VPD0, 5.0);
			parameters.put(GeoetInputsHandler.PARAM_TL, -5.0);
			parameters.put(GeoetInputsHandler.PARAM_T0, 20.0);
			parameters.put(GeoetInputsHandler.PARAM_TH, 45.0);

			Map<String, String> timeseries = new LinkedHashMap<>();
			timeseries.put(GeoetInputsHandler.VAR_AIR_TEMPERATURE, siteDir + "AirTemperature_hourly.csv");
			timeseries.put(GeoetInputsHandler.VAR_NET_RADIATION, siteDir + "Solar_Radiation_mean_hourly.csv");
			timeseries.put(GeoetInputsHandler.VAR_SOIL_FLUX, siteDir + "SpikeII_nan.csv");
			timeseries.put(GeoetInputsHandler.VAR_ATMOSPHERIC_PRESSURE, siteDir + "SpikeII_nan.csv");

			writeGeoetParameters(db, parameters);
			writeGeoetTimeseries(db, timeseries);
			writeRootDensityIc(db, rootDensityIC);
		}
		System.out.println("Wrote " + outPath);
	}

	/** Reads the first feature's centroid from {@code shpPath} and samples {@code demPath} there. */
	private static double readElevationAtCentroid(String shpPath, String demPath) throws Exception {
		SimpleFeatureCollection centroids = OmsVectorReader.readVector(shpPath);
		
		Coordinate coordinate;
		try (FeatureIterator<SimpleFeature> it = centroids.features()) {
			SimpleFeature feature = it.next();
			coordinate = ((Geometry) feature.getDefaultGeometry()).getCentroid().getCoordinate();
		}

		try (HMRaster dem = HMRaster.fromFile(demPath)) {
			return dem.getValue(coordinate);
		}
	}

	/**
	 * Writes {@code grid_geometry.csv}, {@code pseudo_cell.csv},
	 * {@code swrc_parameter_types.csv}, {@code swrc_parameters.csv} and
	 * {@code initial_condition.csv} into {@code csvFolder}, matching
	 * {@link Whetgeo1DInputsHandler}'s CSV schema exactly, reproducing the real
	 * grid in {@code netcdfGridPath} (verified to be exactly recoverable: the
	 * grid's 250 real cells are contiguous with no gaps, so one
	 * {@code grid_geometry} row per cell (K=1) - using each cell's own
	 * interface eta as that row's {@code eta} - reproduces the exact same
	 * centroid on read-back; {@code psi0} is exactly hydrostatic
	 * ({@code psi0[i] = -z[i]}, water table at the domain's bottom boundary,
	 * verified at 6 points spanning the whole column), so 2
	 * {@code initial_condition} endpoints reproduce every real cell's
	 * {@code psi0} exactly via Whetgeo1DInputsHandler's own linear
	 * interpolation - no manual per-cell IC needed).
	 */
	private static double[] writeGridCsvs(String netcdfGridPath, File csvFolder) throws Exception {
		try (NetcdfFile nc = NetcdfFile.open(netcdfGridPath)) {
			int kmax = readInt(nc, "KMAX")[0];
			double[] eta = readDoubles(nc, "eta");
			double[] controlVolume = readDoubles(nc, "controlVolume");
			int[] equationStateID = readInt(nc, "equationStateID");
			int[] parameterID = readInt(nc, "parameterID");
			double[] thetaS = readDoubles(nc, "thetaS");
			double[] thetaR = readDoubles(nc, "thetaR");
			double[] thetaWP = readDoubles(nc, "thetaWP");
			double[] thetaFC = readDoubles(nc, "thetaFC");
			double[] n = readDoubles(nc, "par1SWRC");
			double[] alpha = readDoubles(nc, "par2SWRC");
			double[] alphaSS = readDoubles(nc, "alphaSpecificStorage");
			double[] betaSS = readDoubles(nc, "betaSpecificStorage");
			double[] ks = readDoubles(nc, "ks");
			double[] root0 = readDoubles(nc, "root0");

			// last index is the pond pseudo-cell (equationStateID=0); real soil cells are 0..numRealCells-1
			int numRealCells = kmax - 1;

			// ---- grid_geometry.csv: one row per real cell (K=1), top-to-bottom, + bottom sentinel
			try (PrintWriter w = csv(csvFolder, "grid_geometry.csv")) {
				w.println("type,eta,K,equationStateID,parameterID");
				for (int i = numRealCells - 1; i >= 0; i--) {
					double etaTop = eta[i] + 0.5 * controlVolume[i];
					w.printf("L,%.10f,1,%d,%d%n", etaTop, equationStateID[i], parameterID[i]);
				}
				double etaBottomDomain = eta[0] - 0.5 * controlVolume[0];
				w.printf("L,%.10f,-9999,-9999,-9999%n", etaBottomDomain);
			}

			// ---- pseudo_cell.csv: the pond, reusing its own real equationStateID/parameterID/controlVolume
			try (PrintWriter w = csv(csvFolder, "pseudo_cell.csv")) {
				w.println("stackOrder,equationStateID,parameterID,controlVolume,note");
				w.printf("0,%d,%d,%.10f,Surface ponding reservoir coupled via TOP_COUPLED%n",
						equationStateID[numRealCells], parameterID[numRealCells], controlVolume[numRealCells]);
			}

			// ---- swrc_parameter_types.csv: fixed Van Genuchten 2-parameter dictionary
			try (PrintWriter w = csv(csvFolder, "swrc_parameter_types.csv")) {
				w.println("val1,val2");
				w.println("n,par1");
				w.println("alpha,par2");
			}

			// ---- swrc_parameters.csv: all real parameter sets (index 0 is the unused dummy)
			try (PrintWriter w = csv(csvFolder, "swrc_parameters.csv")) {
				w.println("thetaS,thetaR,n,alpha,thetaWP,thetaFC,alphaSpecificStorage,betaSpecificStorage,Ks");
				for (int id = 1; id < thetaS.length; id++) {
					w.printf("%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10e,%.10e,%.10e%n", thetaS[id], thetaR[id], n[id],
							alpha[id], thetaWP[id], thetaFC[id], alphaSS[id], betaSS[id], ks[id]);
				}
			}

			// ---- initial_condition.csv: 2 endpoints reproducing the real hydrostatic psi0 profile exactly
			double etaBottomDomain = eta[0] - 0.5 * controlVolume[0];
			double etaTopDomain = eta[numRealCells - 1] + 0.5 * controlVolume[numRealCells - 1];
			double psi0Top = -(etaTopDomain - etaBottomDomain);
			try (PrintWriter w = csv(csvFolder, "initial_condition.csv")) {
				w.println("eta,psi0,t0");
				w.printf("%.10f,%.10f,293.15%n", etaTopDomain, psi0Top);
				w.printf("%.10f,0.0,293.15%n", etaBottomDomain);
			}

			return java.util.Arrays.copyOf(root0, numRealCells);
		}
	}

	/** Writes the real root-density profile into {@link GeoetInputsHandler}'s {@code root_density_ic} table. */
	private static void writeRootDensityIc(ADb db, double[] rootDensityIC) throws Exception {
		SqlName table = SqlName.m(GeoetInputsHandler.TABLE_ROOT_DENSITY_IC);
		db.createTable(table, GeoetInputsHandler.COL_ID + " INTEGER PRIMARY KEY",
				GeoetInputsHandler.COL_ROOT_DENSITY_IC + " REAL");
		String sql = "INSERT INTO " + table.fixedDoubleName + " (" + GeoetInputsHandler.COL_ID + ", "
				+ GeoetInputsHandler.COL_ROOT_DENSITY_IC + ") VALUES (?, ?)";
		db.execOnConnection(conn -> {
			boolean autoCommit = conn.getAutoCommit();
			conn.setAutoCommit(false);
			try (IHMPreparedStatement ps = conn.prepareStatement(sql)) {
				for (int i = 0; i < rootDensityIC.length; i++) {
					ps.setInt(1, i);
					ps.setDouble(2, rootDensityIC[i]);
					ps.addBatch();
				}
				ps.executeBatch();
				conn.commit();
				conn.setAutoCommit(autoCommit);
			}
			return null;
		});
	}

	private static PrintWriter csv(File folder, String name) throws Exception {
		return new PrintWriter(new FileWriter(new File(folder, name)));
	}

	private static double[] readDoubles(NetcdfFile nc, String varName) throws Exception {
		Variable v = nc.findVariable(varName);
		Array arr = v.read();
		double[] out = new double[(int) arr.getSize()];
		for (int i = 0; i < out.length; i++) {
			out[i] = arr.getDouble(i);
		}
		return out;
	}

	private static int[] readInt(NetcdfFile nc, String varName) throws Exception {
		Variable v = nc.findVariable(varName);
		Array arr = v.read();
		int[] out = new int[(int) arr.getSize()];
		for (int i = 0; i < out.length; i++) {
			out[i] = arr.getInt(i);
		}
		return out;
	}

	/**
	 * Writes GEOET's {@code parameters} row, same shape as
	 * {@code org.geoframe.geoet.tools.GpkgFixtureBuilder.writeParameters} (that
	 * class is test-scoped in GEOET's own module, not importable here).
	 */
	private static void writeGeoetParameters(ADb db, Map<String, Object> parameters) throws Exception {
		SqlName table = SqlName.m("parameters");

		java.util.List<String> fieldDefs = new java.util.ArrayList<>();
		fieldDefs.add("id INTEGER PRIMARY KEY");
		for (Map.Entry<String, Object> e : parameters.entrySet()) {
			String sqlType = (e.getValue() instanceof String) ? "TEXT" : (e.getValue() instanceof Integer) ? "INTEGER" : "REAL";
			fieldDefs.add(e.getKey() + " " + sqlType);
		}
		db.createTable(table, fieldDefs.toArray(new String[0]));

		String colsCsv = "id, " + String.join(", ", parameters.keySet());
		StringBuilder placeholders = new StringBuilder("?");
		for (int i = 0; i < parameters.size(); i++) {
			placeholders.append(", ?");
		}
		String sql = "INSERT INTO " + table.fixedDoubleName + " (" + colsCsv + ") VALUES (" + placeholders + ")";

		db.execOnConnection(conn -> {
			try (IHMPreparedStatement ps = conn.prepareStatement(sql)) {
				ps.setInt(1, 1);
				int pos = 2;
				for (Object v : parameters.values()) {
					if (v instanceof String s) {
						ps.setString(pos++, s);
					} else if (v instanceof Integer i) {
						ps.setInt(pos++, i);
					} else {
						ps.setDouble(pos++, ((Number) v).doubleValue());
					}
				}
				ps.addBatch();
				ps.executeBatch();
			}
			return null;
		});
	}

	/**
	 * Writes one {@code timeseries_<variableName>} table per entry, same shape
	 * as {@code org.geoframe.geoet.tools.GpkgFixtureBuilder.writeTimeseries}.
	 */
	private static void writeGeoetTimeseries(ADb db, Map<String, String> timeseriesCsvByColumn) throws Exception {
		for (Map.Entry<String, String> e : timeseriesCsvByColumn.entrySet()) {
			String variableName = e.getKey();
			String csvPath = e.getValue();

			OmsTimeSeriesReader reader = new OmsTimeSeriesReader();
			reader.file = csvPath;
			reader.read();
			reader.close();

			SqlName table = SqlName.m("timeseries_" + variableName);
			db.createTable(table, "timestamp INTEGER PRIMARY KEY", variableName + " REAL");

			String sql = "INSERT INTO " + table.fixedDoubleName + " (timestamp, " + variableName + ") VALUES (?, ?)";

			db.execOnConnection(conn -> {
				boolean autoCommit = conn.getAutoCommit();
				conn.setAutoCommit(false);
				try (IHMPreparedStatement ps = conn.prepareStatement(sql)) {
					for (Map.Entry<DateTime, double[]> row : reader.outData.entrySet()) {
						ps.setLong(1, row.getKey().getMillis());
						ps.setDouble(2, row.getValue()[0]);
						ps.addBatch();
					}
					ps.executeBatch();
					conn.commit();
					conn.setAutoCommit(autoCommit);
				}
				return null;
			});
		}
	}
}
