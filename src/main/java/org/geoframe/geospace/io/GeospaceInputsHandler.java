package org.geoframe.geospace.io;

import org.hortonmachine.dbs.compat.ADb;
import org.hortonmachine.dbs.compat.EDb;
import org.hortonmachine.dbs.utils.SqlName;

/**
 * Reads whatever the GEOSPACE-1D coupled stack needs from the shared input
 * gpkg that no sibling project's own input handler covers - the input-side
 * counterpart to {@link GeospaceOutputsHandler} owning what's unique to the
 * coupled stack on the output side. Intended to grow with whatever else the
 * stack needs that isn't a sibling's own concern.
 *
 * <p>
 * {@code thetaWP}/{@code thetaFC} used to live here as a stopgap (WHETGEO-1D's
 * own {@code Whetgeo1DInputsHandler} didn't read them yet); now that
 * WHETGEO-1D's reader has been extended to read them directly (they're really
 * its own soil-parameter concern), this class only owns {@link #rootDensityIC}.
 */
public class GeospaceInputsHandler implements AutoCloseable {

	public static final String TABLE_ROOT_DENSITY_IC = "root_density_ic";
	public static final String COL_ID = "id";
	public static final String COL_ROOT_DENSITY_IC = "rootDensityIC";

	private final ADb db;
	private final boolean ownsDb;

	/**
	 * Per real soil cell (0-indexed, same convention as Whetgeo1DInputsHandler's
	 * z/eta), the real root-density profile GEOET's {@code RootDensitySolver}
	 * needs when configured with {@code rootDensityModel = "CostantMethod"} -
	 * that model just returns {@code CurrentStepInput.rootDensityIC} unchanged,
	 * it doesn't compute anything from {@code rootDepth} analytically. No
	 * sibling schema has a place for this (it's spatial root data, not a soil
	 * or met property), so it lives here, {@code null} if the table is absent.
	 */
	public double[] rootDensityIC;

	public GeospaceInputsHandler(ADb db) {
		this.db = db;
		this.ownsDb = false;
	}

	public GeospaceInputsHandler(String gpkgPath) throws Exception {
		this.db = EDb.GEOPACKAGE.getDb();
		this.db.open(gpkgPath);
		this.ownsDb = true;
	}

	@Override
	public void close() throws Exception {
		if (ownsDb) {
			db.close();
		}
	}

	public void read() throws Exception {
		SqlName table = SqlName.m(TABLE_ROOT_DENSITY_IC);
		if (!db.hasTable(table)) {
			return;
		}
		int n = (int) db.getCount(table);
		rootDensityIC = new double[n];
		String sql = "SELECT " + COL_ID + ", " + COL_ROOT_DENSITY_IC + " FROM " + table.fixedDoubleName + " ORDER BY "
				+ COL_ID;
		db.execOnResultSet(sql, rs -> {
			while (rs.next()) {
				rootDensityIC[rs.getInt(1)] = rs.getDouble(2);
			}
			return null;
		});
	}
}
