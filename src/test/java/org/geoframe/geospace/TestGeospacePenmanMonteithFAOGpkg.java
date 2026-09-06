/*
 * GNU GPL v3 License
 *
 * Copyright 2019 Concetta D'Amato
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.geoframe.geospace;

import java.io.File;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.geoframe.brokergeo.core.fluxsplit.FluxSplitMethod;
import org.geoframe.brokergeo.core.state.BGCurrentStepInput;
import org.geoframe.brokergeo.core.state.BGProblemQuantities;
import org.geoframe.brokergeo.io.BrokerGeoOutputsHandler;
import org.geoframe.brokergeo.solvers.ETsBrokerOneFluxSolverMain;
import org.geoframe.geoet.core.config.Parameters;
import org.geoframe.geoet.core.state.ETCurrentStepInput;
import org.geoframe.geoet.core.state.ETProblemQuantities;
import org.geoframe.geoet.io.GeoetInputsHandler;
import org.geoframe.geoet.io.GeoetOutputsHandler;
import org.geoframe.geoet.io.InputPreprocessor;
import org.geoframe.geoet.solvers.JarvisStressFactorSolverWithNetRadiation;
import org.geoframe.geoet.solvers.PenmanMonteithFAOSolverWithStressFactor;
import org.geoframe.geoet.solvers.RootDensitySolver;
import org.geoframe.whetgeo1d.core.boundaryconditions.IBoundaryCondition.RichardsBoundaryConditionType;
import org.geoframe.whetgeo1d.io.Whetgeo1DInputsHandler;
import org.geoframe.whetgeo1d.io.Whetgeo1DOutputsHandler;
import org.geoframe.whetgeo1d.solvers.RichardsSolverWithRootWaterUptake1D;
import org.hortonmachine.dbs.compat.ADb;
import org.hortonmachine.dbs.compat.EDb;
import org.hortonmachine.gears.libs.monitor.LogProgressMonitor;
import org.hortonmachine.gears.utils.time.ETimeUtilities;
import org.hortonmachine.gears.utils.time.UtcTimeUtilities;

/**
 * Full-stack GEOSPACE-1D coupling: WHETGEO-1D's root-water-uptake Richards
 * solver, driven each step by GEOET's root-density/Jarvis-stress/
 * Penman-Monteith-FAO solvers and BrokerGEO's one-flux broker.
 *
 * @author Concetta D'Amato, Niccolo' Tubini, Michele Bottazzi and Riccardo Rigon
 * @author Andrea Antonello
 */
public class TestGeospacePenmanMonteithFAOGpkg extends GeospaceTestCase {

	public void testPenmanMonteithFAO() throws Exception {

		String startDate = "2015-01-01 00:00";
		String endDate = "2015-02-01 02:00";

		String inputsPath = getRes("/input/gpkg/CavonePenmanMonteithFAO.gpkg");
		String outputPath = getTmpPath("CavonePenmanMonteithFAOOutput", ".gpkg");
		new File(outputPath).delete();

		try (ADb inDb = EDb.GEOPACKAGE.getDb()) {
			inDb.open(inputsPath);

			var whetgeoIn = new Whetgeo1DInputsHandler(inDb);
			whetgeoIn.read();
			var geoetIn = new GeoetInputsHandler(inDb);
			geoetIn.read();

			int KMAX = whetgeoIn.KMAX;
			int KREAL = KMAX - 1; // real soil cells, excludes the pond pseudo-cell
			double rootDepth = geoetIn.getParameterDouble(GeoetInputsHandler.PARAM_ROOTS_DEPTH);

			var topBC = RichardsBoundaryConditionType.TOP_COUPLED;
			var bottomBC = RichardsBoundaryConditionType.BOTTOM_FREE_DRAINAGE;

			// ---- WHETGEO-1D: Richards with root water uptake ----
			RichardsSolverWithRootWaterUptake1D richards = new RichardsSolverWithRootWaterUptake1D();
			richards.z = whetgeoIn.z;
			richards.spaceDeltaZ = whetgeoIn.spaceDelta;
			richards.psiIC = whetgeoIn.psi;
			richards.temperature = whetgeoIn.temperatureIC;
			richards.controlVolume = whetgeoIn.controlVolume;
			richards.ks = whetgeoIn.Ks;
			richards.thetaS = whetgeoIn.thetaS;
			richards.thetaR = whetgeoIn.thetaR;
			richards.thetaWP = whetgeoIn.thetaWP;
			richards.thetaFC = whetgeoIn.thetaFC;
			richards.par1SWRC = whetgeoIn.par1SWRC;
			richards.par2SWRC = whetgeoIn.par2SWRC;
			richards.par3SWRC = whetgeoIn.par3SWRC;
			richards.par4SWRC = whetgeoIn.par4SWRC;
			richards.par5SWRC = whetgeoIn.par5SWRC;
			richards.alphaSpecificStorage = whetgeoIn.alphaSS;
			richards.betaSpecificStorage = whetgeoIn.betaSS;
			richards.inEquationStateID = whetgeoIn.equationStateID;
			richards.inParameterID = whetgeoIn.parameterID;
			richards.beta0 = -766.45;
			richards.referenceTemperatureSWRC = 293.15;
			richards.maxPonding = 0.0;
			richards.typeClosureEquation = new String[] { "Water Depth", "Van Genuchten" };
			richards.typeEquationState = new String[] { "Water Depth", "Van Genuchten" };
			richards.typeUHCModel = new String[] { "", "Mualem Van Genuchten" };
			richards.typeUHCTemperatureModel = "notemperature";
			richards.interfaceHydraulicConductivityModel = "max";
			richards.topBCType = topBC;
			richards.bottomBCType = bottomBC;
			richards.delta = 0;
			richards.tTimeStep = 3600;
			richards.timeDelta = 3600;
			richards.newtonTolerance = Math.pow(10, -9);
			richards.nestedNewton = 1;
			richards.picardIteration = 1;
			richards.stationID = 0;

			// ---- GEOET: root density + Jarvis stress factor + Penman-Monteith-FAO ----
			var geoetInput = new ETCurrentStepInput();
			var geoetVars = new ETProblemQuantities();
			geoetVars.rootDepth = rootDepth;
			geoetInput.z = richards.z;
			geoetInput.rootDensityIC = geoetIn.rootDensityIC;
			geoetInput.time = 3600;

			InputPreprocessor inputPreprocessor = new InputPreprocessor();
			inputPreprocessor.parameters = new Parameters();
			inputPreprocessor.variables = geoetVars;
			inputPreprocessor.input = geoetInput;
			inputPreprocessor.z = richards.z;
			inputPreprocessor.rootIC = geoetIn.rootDensityIC;
			inputPreprocessor.rootDepth = rootDepth;
			inputPreprocessor.tStartDate = startDate;
			inputPreprocessor.temporalStep = 60;
			inputPreprocessor.elevation = geoetIn.getParameterDouble(GeoetInputsHandler.PARAM_ELEVATION);
			inputPreprocessor.canopyHeight = geoetIn.getParameterDouble(GeoetInputsHandler.PARAM_CANOPY_HEIGHT);
			inputPreprocessor.ID = 0;

			RootDensitySolver rootDensitySolver = new RootDensitySolver();
			rootDensitySolver.input = geoetInput;
			rootDensitySolver.variables = geoetVars;
			rootDensitySolver.rootDensityModel = "CostantMethod";

			JarvisStressFactorSolverWithNetRadiation jarvis = new JarvisStressFactorSolverWithNetRadiation();
			jarvis.input = geoetInput;
			jarvis.variables = geoetVars;
			jarvis.thetaWp = whetgeoIn.thetaWP;
			jarvis.thetaFc = whetgeoIn.thetaFC;
			jarvis.z = richards.z;
			jarvis.deltaZ = whetgeoIn.spaceDelta;
			jarvis.ID = whetgeoIn.parameterID;
			jarvis.stressFactorModel = "LinearStressFactor";
			jarvis.representativeStressFactorModel = "AverageMethod";
			jarvis.alpha = geoetIn.getParameterDouble(GeoetInputsHandler.PARAM_ALPHA);
			jarvis.thetaR = geoetIn.getParameterDouble(GeoetInputsHandler.PARAM_THETA);
			jarvis.VPD0 = geoetIn.getParameterDouble(GeoetInputsHandler.PARAM_VPD0);
			jarvis.T0 = geoetIn.getParameterDouble(GeoetInputsHandler.PARAM_T0);
			jarvis.Tl = geoetIn.getParameterDouble(GeoetInputsHandler.PARAM_TL);
			jarvis.Th = geoetIn.getParameterDouble(GeoetInputsHandler.PARAM_TH);
			jarvis.useRadiationStress = geoetIn.getParameterInt(GeoetInputsHandler.PARAM_USE_RADIATION_STRESS) != 0;
			jarvis.useTemperatureStress = geoetIn.getParameterInt(GeoetInputsHandler.PARAM_USE_TEMPERATURE_STRESS) != 0;
			jarvis.useVDPStress = geoetIn.getParameterInt(GeoetInputsHandler.PARAM_USE_VDP_STRESS) != 0;
			jarvis.useWaterStress = geoetIn.getParameterInt(GeoetInputsHandler.PARAM_USE_WATER_STRESS) != 0;

			PenmanMonteithFAOSolverWithStressFactor pmFAO = new PenmanMonteithFAOSolverWithStressFactor();
			pmFAO.input = geoetInput;
			pmFAO.variables = geoetVars;
			pmFAO.parameters = new Parameters();
			pmFAO.soilFluxParameterDay = geoetIn.getParameterDouble(GeoetInputsHandler.PARAM_SOIL_FLUX_PARAMETER_DAY);
			pmFAO.soilFluxParameterNight = geoetIn.getParameterDouble(GeoetInputsHandler.PARAM_SOIL_FLUX_PARAMETER_NIGHT);

			// ---- BrokerGEO: splits the coupled ET total across the root zone ----
			var brokerInput = new BGCurrentStepInput();
			var brokerVars = new BGProblemQuantities();
			brokerInput.z = richards.z;
			brokerInput.deltaZ = whetgeoIn.spaceDelta;
			brokerInput.etaR = rootDepth;

			ETsBrokerOneFluxSolverMain broker = new ETsBrokerOneFluxSolverMain();
			broker.input = brokerInput;
			broker.variables = brokerVars;
			broker.representativeTsModel = FluxSplitMethod.AVERAGE_WEIGHTED;
			broker.useWaterStress = jarvis.useWaterStress;

			double[] stressedETsForNextStep = new double[KMAX];

			double maxAbsWaterVolumeError = 0.0;
			double maxThetaBoundsViolation = 0.0;
			double maxThetaWpViolation = 0.0;
			double cumulativeET = 0.0;

			ADb outDb = EDb.GEOPACKAGE.getDb();
			outDb.open(outputPath);

			try (whetgeoIn;
					geoetIn;
					outDb;
					var topBCIterator = whetgeoIn.iterateTimeseries("timeseries_topBC", startDate, endDate, 500);
					var bottomBCIterator = whetgeoIn.iterateTimeseries("timeseries_bottomBC", startDate, endDate, 500);
					var airTempIterator = geoetIn.iterateTimeseries(GeoetInputsHandler.VAR_AIR_TEMPERATURE, startDate,
							endDate, 500);
					var windIterator = geoetIn.iterateTimeseries(GeoetInputsHandler.VAR_WIND_VELOCITY, startDate,
							endDate, 500);
					var humidityIterator = geoetIn.iterateTimeseries(GeoetInputsHandler.VAR_RELATIVE_HUMIDITY, startDate,
							endDate, 500);
					var netRadIterator = geoetIn.iterateTimeseries(GeoetInputsHandler.VAR_NET_RADIATION, startDate,
							endDate, 500);
					var soilFluxIterator = geoetIn.iterateTimeseries(GeoetInputsHandler.VAR_SOIL_FLUX, startDate,
							endDate, 500);
					var pressureIterator = geoetIn.iterateTimeseries(GeoetInputsHandler.VAR_ATMOSPHERIC_PRESSURE,
							startDate, endDate, 500);
					var whetgeoOut = new Whetgeo1DOutputsHandler(outDb, 500);
					var brokerOut = new BrokerGeoOutputsHandler(outDb, 500);
					var geoetOut = new GeoetOutputsHandler(outDb, 500)) {

				whetgeoOut.eta = whetgeoIn.eta;
				whetgeoOut.etaDual = whetgeoIn.etaDual;
				whetgeoOut.controlVolume = whetgeoIn.controlVolume;
				whetgeoOut.psi = whetgeoIn.psi;
				whetgeoOut.temperatureIC = whetgeoIn.temperatureIC;
				whetgeoOut.parameterID = whetgeoIn.parameterID;
				whetgeoOut.swrcThetaS = whetgeoIn.thetaS;
				whetgeoOut.swrcThetaR = whetgeoIn.thetaR;
				whetgeoOut.swrcKs = whetgeoIn.Ks;
				whetgeoOut.swrcN = whetgeoIn.par1SWRC;
				whetgeoOut.swrcAlpha = whetgeoIn.par2SWRC;
				whetgeoOut.topBCType = topBC.name();
				whetgeoOut.bottomBCType = bottomBC.name();

				brokerOut.eta = Arrays.copyOf(whetgeoIn.eta, KREAL);

				var pm = new LogProgressMonitor("TestGeospacePenmanMonteithFAOGpkg");
				pm.beginTask(" -> Running GEOSPACE-1D full-stack test", -1);
				int iteration = 0;
				while (topBCIterator.next() && bottomBCIterator.next() && airTempIterator.next()
						&& windIterator.next() && humidityIterator.next() && netRadIterator.next()
						&& soilFluxIterator.next() && pressureIterator.next()) {

					long timestamp = topBCIterator.timestamp();

					// ---- Richards: solves using the broker's PREVIOUS-step stressedETs (lagged
					// coupling) ----
					richards.inTopBC = new HashMap<>(Map.of(richards.stationID, topBCIterator.values()));
					richards.inBottomBC = new HashMap<>(Map.of(richards.stationID, bottomBCIterator.values()));
					richards.inCurrentDate = ETimeUtilities.INSTANCE.TIME_FORMATTER_UTC.format(new Date(timestamp));
					richards.stressedETs = stressedETsForNextStep;

					richards.solve();

					maxAbsWaterVolumeError = Math.max(maxAbsWaterVolumeError, Math.abs(richards.outErrorVolume));

					// ---- GEOET + Broker: fed by THIS step's freshly computed theta, feeding the
					// NEXT Richards call ----
					inputPreprocessor.inAirTemperature = new HashMap<>(Map.of(0, airTempIterator.values()));
					inputPreprocessor.inWindVelocity = new HashMap<>(Map.of(0, windIterator.values()));
					inputPreprocessor.inRelativeHumidity = new HashMap<>(Map.of(0, humidityIterator.values()));
					inputPreprocessor.inNetRadiation = new HashMap<>(Map.of(0, netRadIterator.values()));
					inputPreprocessor.inSoilFlux = new HashMap<>(Map.of(0, soilFluxIterator.values()));
					inputPreprocessor.inAtmosphericPressure = new HashMap<>(Map.of(0, pressureIterator.values()));
					inputPreprocessor.process();

					rootDensitySolver.solve();

					jarvis.theta = richards.outWaterContent;
					jarvis.solve();

					pmFAO.stressFactor = jarvis.stressSun;
					pmFAO.process();

					brokerInput.transpiration = pmFAO.evapoTranspirationPM;
					brokerInput.rootDensity = rootDensitySolver.defRootDensity;
					brokerInput.GnT = jarvis.GnT;
					brokerInput.g = jarvis.g;

					broker.solve();

					stressedETsForNextStep = Arrays.copyOf(broker.stressedETs, KMAX);
					cumulativeET += pmFAO.evapoTranspirationPM;

					for (int i = 0; i < KREAL; i++) {
						int pid = whetgeoIn.parameterID[i];
						double theta = richards.outWaterContent[i];
						maxThetaWpViolation = Math.max(maxThetaWpViolation, whetgeoIn.thetaWP[pid] - theta);
						double boundsViolation = Math.max(whetgeoIn.thetaR[pid] - theta,
								theta - whetgeoIn.thetaS[pid]);
						maxThetaBoundsViolation = Math.max(maxThetaBoundsViolation, boundsViolation);
					}

					// ---- write outputs: standard WHETGEO-1D tables + BrokerGEO's/GEOET's own
					// uptake/ET tables ----
					whetgeoOut.timestamp = timestamp;
					whetgeoOut.theta = richards.outWaterContent;
					whetgeoOut.waterSuction = richards.outWaterSuctions;
					whetgeoOut.darcyVelocity = richards.outDarcyVelocity;
					whetgeoOut.errorVolume = richards.outErrorVolume;
					whetgeoOut.topBC = richards.outTopBCValue;
					whetgeoOut.bottomBC = richards.outBottomBCValue;
					whetgeoOut.write();

					brokerOut.timestamp = timestamp;
					brokerOut.stressedETs = Arrays.copyOf(broker.stressedETs, KREAL);
					brokerOut.writeStep();

					geoetOut.timestamp = timestamp;
					geoetOut.evapoTranspiration = pmFAO.evapoTranspirationPM;
					geoetOut.fluxEvapoTranspiration = geoetVars.fluxEvapoTranspirationPM;
					geoetOut.write();

					if (iteration++ % 100 == 0) {
						pm.message(" -> processed timestamp " + UtcTimeUtilities.quickToString(timestamp));
					}
				}
				pm.done();
			}

			assertTrue("water volume balance residual too large: " + maxAbsWaterVolumeError,
					maxAbsWaterVolumeError < 1e-6);
			assertTrue("water content left its physical thetaR..thetaS bounds by " + maxThetaBoundsViolation,
					maxThetaBoundsViolation < 2e-3);
			assertTrue("water content dropped below thetaWP (root-uptake clamp failed) by " + maxThetaWpViolation,
					maxThetaWpViolation < 1e-6);
			assertTrue("coupled ET was never positive over the whole run - the stack looks like a no-op",
					cumulativeET > 0);

			assertGpkgColumnMatchesGolden("/golden/TestGeospacePenmanMonteithFAOGpkg/EvapoTranspiration.csv",
					outputPath, "geoframe_geoet_output_results", "timestamp", "evapo_transpiration");
			assertGpkgColumnMatchesGolden("/golden/TestGeospacePenmanMonteithFAOGpkg/FluxEvapoTranspiration.csv",
					outputPath, "geoframe_geoet_output_results", "timestamp", "flux_evapo_transpiration");
		}
	}
}
