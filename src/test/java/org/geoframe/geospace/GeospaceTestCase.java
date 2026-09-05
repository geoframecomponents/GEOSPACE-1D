package org.geoframe.geospace;

import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.TreeMap;

import org.hortonmachine.dbs.compat.ADb;
import org.hortonmachine.dbs.compat.EDb;
import org.hortonmachine.dbs.utils.SqlName;
import org.hortonmachine.gears.io.timeseries.OmsTimeSeriesReader;
import org.joda.time.DateTime;

import junit.framework.TestCase;

public abstract class GeospaceTestCase extends TestCase {

	protected String getRes(String name) throws Exception {
		URL url = this.getClass().getResource(name);
		if (url == null) {
			throw new Exception("Resource not found: " + name);
		}
		return Paths.get(url.toURI()).toString();
	}

	protected String getTmpPath(String prefix, String ext) throws Exception {
		File tempFile = Files.createTempFile(prefix, ext).toFile();
		return tempFile.getAbsolutePath();
	}

	/**
	 * Reads {@code resultColumn} back out of {@code tableName} in the just-written
	 * output GeoPackage at {@code outputGpkgPath}, ordered by {@code
	 * timestampColumn}, and compares it against a frozen OMS-format golden
	 * timeseries CSV at {@code goldenResourcePath} - value by value, keyed on
	 * timestamp.
	 */
	protected void assertGpkgColumnMatchesGolden(String goldenResourcePath, String outputGpkgPath, String tableName,
			String timestampColumn, String resultColumn) throws Exception {
		OmsTimeSeriesReader goldenReader = new OmsTimeSeriesReader();
		goldenReader.file = getRes(goldenResourcePath);
		goldenReader.read();
		goldenReader.close();

		Map<Long, Double> golden = new TreeMap<>();
		for (Map.Entry<DateTime, double[]> e : goldenReader.outData.entrySet()) {
			golden.put(e.getKey().getMillis(), e.getValue()[0]);
		}

		Map<Long, Double> actual = new TreeMap<>();
		try (ADb db = EDb.GEOPACKAGE.getDb()) {
			db.open(outputGpkgPath);
			String sql = "SELECT " + timestampColumn + ", " + resultColumn + " FROM "
					+ SqlName.m(tableName).fixedDoubleName + " ORDER BY " + timestampColumn;
			db.execOnResultSet(sql, rs -> {
				while (rs.next()) {
					actual.put(rs.getLong(1), rs.getDouble(2));
				}
				return null;
			});
		}

		assertEquals("Row count mismatch reading back " + resultColumn + " from " + tableName, golden.size(),
				actual.size());
		for (Map.Entry<Long, Double> e : golden.entrySet()) {
			Double actualValue = actual.get(e.getKey());
			assertNotNull("Missing timestamp " + e.getKey() + " in " + tableName + " for " + resultColumn,
					actualValue);
			assertEquals("Value mismatch for " + resultColumn + " at " + e.getKey(), e.getValue(), actualValue, 1e-9);
		}
	}
}
