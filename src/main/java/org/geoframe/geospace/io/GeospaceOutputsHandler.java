package org.geoframe.geospace.io;

import java.util.ArrayList;
import java.util.List;

import org.geoframe.geoet.io.GeoetOutputsHandler;
import org.hortonmachine.dbs.compat.ADb;
import org.hortonmachine.dbs.compat.EDb;
import org.hortonmachine.dbs.compat.IHMPreparedStatement;
import org.hortonmachine.dbs.utils.SqlName;
import org.hortonmachine.gears.io.geoframe.whetgeo.Whetgeo1DOutputSchema;

/**
 * DB-based output handler for the one physical quantity unique to the
 * GEOSPACE-1D coupled stack: the per-cell, per-timestep root water uptake
 * ({@code stressedETs}) that {@code ETsBrokerOneFluxSolverMain} computes and
 * feeds into {@code RichardsSolverWithRootWaterUptake1D} - no equivalent
 * exists in either WHETGEO-1D's or GEOET's own output schema.
 *
 * <p>
 * Mirrors WHETGEO-1D's own {@code Whetgeo1DOutputsHandler}: same
 * {@code timestamp x eta} long-format {@code output_state} convention (reuses
 * {@link Whetgeo1DOutputSchema#COL_TIMESTAMP}/{@link Whetgeo1DOutputSchema#COL_ETA}
 * as the join key, so this table lines up row-for-row with WHETGEO-1D's own
 * {@code geoframe_whetgeo1d_output_state} when both are written into the same
 * gpkg), same {@code geoframe_<component>_output_*} table naming, same
 * buffered set-fields-then-{@link #write()}-once-per-step usage.
 *
 * <p>
 * Also writes the coupled ET total into GEOET's own {@code
 * geoframe_geoet_output_results} table (reusing {@link GeoetOutputsHandler}'s
 * public table/column constants, so it's the properly-named, recognizable
 * GEOET output table) rather than using {@link GeoetOutputsHandler} itself:
 * that class only has a path-based constructor, which unconditionally
 * deletes any pre-existing file at that path - unsafe to combine with
 * another writer already holding the same output file open (deleting a file
 * out from under an open connection to it silently orphans that connection's
 * writes). Sharing this class's own {@link ADb} instead avoids that.
 */
public class GeospaceOutputsHandler implements AutoCloseable {

	public static final String PREFIX = "geoframe_geospace";
	public static final String TABLE_OUTPUT_UPTAKE = PREFIX + "_output_uptake";

	public static final String COL_ID = "id";
	public static final String COL_TIMESTAMP = Whetgeo1DOutputSchema.COL_TIMESTAMP;
	public static final String COL_ETA = Whetgeo1DOutputSchema.COL_ETA;
	public static final String COL_STRESSED_ETS = "stressed_ets";

	/** Set once before the first {@link #write()}: the real soil cells' eta coordinates. */
	public double[] eta;

	// mandatory per-step output - the row key every step has
	public long timestamp;
	/** Per real soil cell (same length/order as {@link #eta}), root water uptake applied this step. */
	public double[] stressedETs;

	/** Optional: the coupled stack's total ET for this step, written into GEOET's own output table if non-null. */
	public Double evapoTranspiration;

	/**
	 * When true, an existing {@link #TABLE_OUTPUT_UPTAKE} is dropped and
	 * recreated on the first {@link #write()} call.
	 */
	public boolean dropAndRecreate = false;

	private final ADb db;
	private final int bufferSize;

	private boolean initialized = false;
	private int KMAX;
	private String sqlInsert;
	private boolean withEvapoTranspiration;
	private String sqlInsertEt;

	private final List<Long> tsBuf = new ArrayList<>();
	private final List<double[]> uptakeBuf = new ArrayList<>();
	private final List<Double> etBuf = new ArrayList<>();

	public GeospaceOutputsHandler(ADb db, int bufferSize) {
		this.db = db;
		this.bufferSize = bufferSize;
	}

	public GeospaceOutputsHandler(String dbPath, int bufferSize) throws Exception {
		this.db = EDb.GEOPACKAGE.getDb();
		this.db.open(dbPath);
		this.bufferSize = bufferSize;
	}

	/** Accumulate the current step and flush to DB when the buffer is full. */
	public void write() throws Exception {
		if (!initialized) {
			initialize();
		}
		tsBuf.add(timestamp);
		uptakeBuf.add(stressedETs.clone());
		if (withEvapoTranspiration) {
			etBuf.add(evapoTranspiration);
		}

		if (tsBuf.size() >= bufferSize) {
			flush();
		}
	}

	/** Flush remaining rows and close. */
	@Override
	public void close() throws Exception {
		flush();
	}

	private void initialize() throws Exception {
		KMAX = eta.length;
		withEvapoTranspiration = (evapoTranspiration != null);

		SqlName uptakeTable = SqlName.m(TABLE_OUTPUT_UPTAKE);

		if (dropAndRecreate) {
			db.executeInsertUpdateDeleteSql("DROP TABLE IF EXISTS \"" + TABLE_OUTPUT_UPTAKE + "\"");
			if (withEvapoTranspiration) {
				db.executeInsertUpdateDeleteSql("DROP TABLE IF EXISTS \"" + GeoetOutputsHandler.TABLE_OUTPUT_RESULTS + "\"");
			}
		}

		if (!db.hasTable(uptakeTable)) {
			db.createTable(uptakeTable, COL_ID + " INTEGER PRIMARY KEY", COL_TIMESTAMP + " INTEGER",
					COL_ETA + " REAL", COL_STRESSED_ETS + " REAL");
			db.createIndex(uptakeTable, COL_TIMESTAMP, false);
			db.createIndex(uptakeTable, COL_ETA, false);
		}

		sqlInsert = String.format("""
				INSERT INTO %s (%s, %s, %s)
				VALUES (?, ?, ?)
				""", TABLE_OUTPUT_UPTAKE, COL_TIMESTAMP, COL_ETA, COL_STRESSED_ETS);

		if (withEvapoTranspiration) {
			SqlName etTable = SqlName.m(GeoetOutputsHandler.TABLE_OUTPUT_RESULTS);
			if (!db.hasTable(etTable)) {
				db.createTable(etTable, GeoetOutputsHandler.COL_TIMESTAMP + " INTEGER PRIMARY KEY",
						GeoetOutputsHandler.COL_EVAPO_TRANSPIRATION + " REAL");
			}
			sqlInsertEt = String.format("""
					INSERT INTO %s (%s, %s)
					VALUES (?, ?)
					""", GeoetOutputsHandler.TABLE_OUTPUT_RESULTS, GeoetOutputsHandler.COL_TIMESTAMP,
					GeoetOutputsHandler.COL_EVAPO_TRANSPIRATION);
		}

		initialized = true;
	}

	private void flush() throws Exception {
		if (tsBuf.isEmpty())
			return;
		int n = tsBuf.size();

		db.execOnConnection(conn -> {
			boolean autoCommit = conn.getAutoCommit();
			conn.setAutoCommit(false);
			try (IHMPreparedStatement ps = conn.prepareStatement(sqlInsert)) {
				for (int r = 0; r < n; r++) {
					long ts = tsBuf.get(r);
					double[] uptake = uptakeBuf.get(r);
					for (int k = 0; k < KMAX; k++) {
						ps.setLong(1, ts);
						ps.setDouble(2, eta[k]);
						ps.setDouble(3, uptake[k]);
						ps.addBatch();
					}
				}
				ps.executeBatch();
			}
			if (withEvapoTranspiration) {
				try (IHMPreparedStatement ps = conn.prepareStatement(sqlInsertEt)) {
					for (int r = 0; r < n; r++) {
						ps.setLong(1, tsBuf.get(r));
						ps.setDouble(2, etBuf.get(r));
						ps.addBatch();
					}
					ps.executeBatch();
				}
			}
			conn.commit();
			conn.setAutoCommit(autoCommit);
			return null;
		});

		tsBuf.clear();
		uptakeBuf.clear();
		if (withEvapoTranspiration) {
			etBuf.clear();
		}
	}
}
