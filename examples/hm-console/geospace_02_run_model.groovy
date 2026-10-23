import org.hortonmachine.HM
import org.geoframe.whetgeo1d.io.Whetgeo1DInputsHandler
import org.geoframe.geoet.io.GeoetInputsHandler
import static org.geoframe.whetgeo1d.core.boundaryconditions.IBoundaryCondition.RichardsBoundaryConditionType.*
import org.geoframe.whetgeo1d.solvers.RichardsSolverWithRootWaterUptake1D
import org.geoframe.geoet.core.state.ETCurrentStepInput
import org.geoframe.geoet.core.state.ETProblemQuantities
import org.geoframe.geoet.solvers.JarvisStressFactorSolverWithNetRadiation
import org.geoframe.geoet.solvers.RootDensitySolver
import org.geoframe.geoet.io.InputPreprocessor
import org.geoframe.geoet.core.config.Parameters
import org.geoframe.brokergeo.core.state.BGCurrentStepInput
import org.geoframe.brokergeo.solvers.ETsBrokerOneFluxSolverMain
import org.geoframe.brokergeo.core.state.BGProblemQuantities
import org.geoframe.brokergeo.core.fluxsplit.FluxSplitMethod
import org.geoframe.geoet.solvers.PriestleyTaylorSolverWithStressFactor
import org.geoframe.whetgeo1d.io.Whetgeo1DOutputsHandler
import org.geoframe.brokergeo.io.BrokerGeoOutputsHandler
import org.geoframe.geoet.io.GeoetOutputsHandler
import org.hortonmachine.gears.utils.chart.Scatter
import org.hortonmachine.gears.utils.chart.StackedTimeSeries



//   1. WHETGEO-1D moves the water: rain in at the top, drainage at the base,
//      and the roots take out what was decided in the previous hour
//   2. GEOET computes how much water the atmosphere asks for (Priestley-Taylor)
//      and how much the plants can give, given the soil water (Jarvis)
//   3. BrokerGEO splits that evapotranspiration over the root zone cells,
//      which is the uptake WHETGEO-1D applies in the next hour

def workspace = "/home/hydrologis/storage/lavori_tmp/UNITN/WHETGEO1D/"
def dataFolder = workspace + "geospace_coupled/" as File
def inGpkg = dataFolder.absolutePath + "/geospace_coupled.gpkg"
def outGpkg = dataFolder.absolutePath + "/geospace_coupled_output.gpkg"
def startDate = "2018-05-10 00:00"
def endDate = "2018-06-29 02:00"
def stationId = 0

def tableTopBC = "timeseries_topBC"

// cleanup first
deleteGpkg(outGpkg)

def inDb = HM.connectGeopackage(inGpkg)
def soilIn = new Whetgeo1DInputsHandler(inDb)
soilIn.read()
def etIn = new GeoetInputsHandler(inDb)
etIn.read()

def kmax = soilIn.KMAX
def pondIndex = kmax - 1 // the pond is the last cell, cells go bottom to top
def rootDepth = etIn.getParameterDouble(GeoetInputsHandler.PARAM_ROOTS_DEPTH)

def topBC = TOP_COUPLED
// free drainage: no bottom boundary data is needed
def bottomBC = BOTTOM_FREE_DRAINAGE

////////////////////////////////////
// 1. WHETGEO-1D: RICHARDS WITH ROOT WATER UPTAKE
////////////////////////////////////
def richards = new RichardsSolverWithRootWaterUptake1D()
// grid, initial condition and soil parameters, wilting point and field capacity included
soilIn.applyTo(richards)
richards.beta0 = -766.45
richards.referenceTemperatureSWRC = 293.15
richards.maxPonding = 0.0
// models, by equationStateID: 0 = pond, 1 = soil
richards.typeClosureEquation = ["Water Depth", "Van Genuchten"] as String[]
richards.typeEquationState = ["Water Depth", "Van Genuchten"] as String[]
richards.typeUHCModel = ["", "Mualem Van Genuchten"] as String[]
richards.typeUHCTemperatureModel = "notemperature"
richards.interfaceHydraulicConductivityModel = "max"
richards.topBCType = topBC
richards.bottomBCType = bottomBC
// numerics: 1 hour steps
richards.tTimeStep = 3600
richards.timeDelta = 3600
richards.delta = 0
richards.newtonTolerance = 1e-9
richards.nestedNewton = 1
richards.picardIteration = 1
richards.stationID = stationId

////////////////////////////////////
// 2. GEOET
////////////////////////////////////
// the GEOET solvers share the input of the current step and their state
def etInput = new ETCurrentStepInput()
def etState = new ETProblemQuantities()
etIn.applyTo(etInput) // root density, elevation
etIn.applyTo(etState) // root depth
etInput.z = richards.z
etInput.time = 3600

// turns the raw data of each hour into the step input
def preprocessor = new InputPreprocessor()
etIn.applyTo(preprocessor)
preprocessor.parameters = new Parameters()
preprocessor.input = etInput
preprocessor.variables = etState
preprocessor.z = richards.z
preprocessor.tStartDate = startDate
preprocessor.ID = stationId

// the roots don't grow in this run
def roots = new RootDensitySolver()
roots.input = etInput
roots.variables = etState
roots.rootDensityModel = "CostantMethod"

// the stress factor: 1 = no stress, 0 = the plants stop transpiring.
// The water stress drops linearly from the field capacity to the wilting point.
def jarvis = new JarvisStressFactorSolverWithNetRadiation()
etIn.applyTo(jarvis, "jarvis")
jarvis.input = etInput
jarvis.variables = etState
jarvis.thetaWp = soilIn.thetaWP
jarvis.thetaFc = soilIn.thetaFC
jarvis.z = richards.z
jarvis.deltaZ = soilIn.spaceDelta
jarvis.ID = soilIn.parameterID
jarvis.stressFactorModel = "LinearStressFactor"
jarvis.representativeStressFactorModel = "RootDensityWeightedMethod"

// the evapotranspiration [mm/h], reduced by the stress factor
def pt = new PriestleyTaylorSolverWithStressFactor()
etIn.applyTo(pt, "priestleyTaylor")
pt.input = etInput
pt.variables = etState
pt.parameters = new Parameters()

////////////////////////////////////
// 3. BROKERGEO: WHERE THE ROOTS TAKE THE WATER
////////////////////////////////////
// each cell gives in proportion to its roots and to its available water
def brokerInput = new BGCurrentStepInput()
brokerInput.z = richards.z
brokerInput.deltaZ = soilIn.spaceDelta
brokerInput.etaR = rootDepth

def broker = new ETsBrokerOneFluxSolverMain()
broker.input = brokerInput
broker.variables = new BGProblemQuantities()
broker.representativeTsModel = FluxSplitMethod.ROOT_WATER_WEIGHTED
broker.useWaterStress = jarvis.useWaterStress

////////////////////////////////////
// RUN
////////////////////////////////////
// what to chart: water content at a few depths
def watchDepths = [-0.1, -0.5, -1.0, -1.5]
def watchCells = watchDepths.collect { depth -> soilIn.cellAt(depth) }
def times = []
def rainValues = []
def etValues = []
def stressValues = []
def thetaValues = watchDepths.collect { [] }
// root water uptake summed over the run, per cell [mm]
def uptakeByCell = new double[pondIndex]

def cumulativeRain = 0.0
def cumulativeET = 0.0
def cumulativeDrainage = 0.0


// no uptake in the first hour: GEOET has not run yet
def uptakeForNextStep = new double[kmax]

def outDb = HM.connectGeopackage(outGpkg)
def stepCount = 0
try (inDb
        outDb
        def topIterator = soilIn.iterateTimeseries(tableTopBC, startDate, endDate, 1000)
        def airTIterator = etIn.iterateTimeseries(GeoetInputsHandler.VAR_AIR_TEMPERATURE, startDate, endDate, 1000)
        def netRadIterator = etIn.iterateTimeseries(GeoetInputsHandler.VAR_NET_RADIATION, startDate, endDate, 1000)
        def soilFluxIterator = etIn.iterateTimeseries(GeoetInputsHandler.VAR_SOIL_FLUX, startDate, endDate, 1000)
        def pressureIterator = etIn.iterateTimeseries(GeoetInputsHandler.VAR_ATMOSPHERIC_PRESSURE, startDate, endDate, 1000)
        def soilOut = new Whetgeo1DOutputsHandler(outDb, 500)
        def brokerOut = new BrokerGeoOutputsHandler(outDb, 500)
        def etOut = new GeoetOutputsHandler(outDb, 500)
    ){
    // each project writes its own tables in the same output geopackage
    soilOut.describe(soilIn, topBC, bottomBC)
    brokerOut.eta = soilIn.eta[0..<pondIndex] as double[]

    def iterators = [topIterator, airTIterator, netRadIterator, soilFluxIterator, pressureIterator]
    while (iterators.every { it.next() }) {
        def timestamp = topIterator.timestamp()
        if (stepCount % 200 == 0) {
            println "Processing timestamp: " + HM.ts2str(timestamp)
        }

        // 1. the water moves, the roots take what was decided in the previous hour
        richards.inTopBC = [(stationId): topIterator.values()]
        richards.inCurrentDate = HM.ts2str(timestamp)
        richards.stressedETs = uptakeForNextStep
        richards.solve()

        // 2. demand and stress, from this hour's weather and soil water
        preprocessor.inAirTemperature = [(stationId): airTIterator.values()]
        preprocessor.inNetRadiation = [(stationId): netRadIterator.values()]
        preprocessor.inSoilFlux = [(stationId): soilFluxIterator.values()]
        preprocessor.inAtmosphericPressure = [(stationId): pressureIterator.values()]
        preprocessor.process()
        roots.solve()
        jarvis.theta = richards.outWaterContent
        jarvis.solve()
        pt.stressFactor = jarvis.stressSun
        pt.process()

        // 3. split it over the root zone: the uptake of the next hour
        brokerInput.transpiration = pt.evapoTranspirationPT
        brokerInput.rootDensity = roots.defRootDensity
        brokerInput.GnT = jarvis.GnT
        brokerInput.g = jarvis.g
        broker.solve()
        uptakeForNextStep = Arrays.copyOf(broker.stressedETs, kmax)

        stepCount++

        // water balance [mm]
        def rain = topIterator.values()[0]
        def thetas = richards.outWaterContent

        // collect data for the chart
        times << timestamp
        rainValues << rain
        etValues << pt.evapoTranspirationPT
        stressValues << jarvis.stressSun
        watchCells.eachWithIndex { cell, k -> thetaValues[k] << thetas[cell] }

        soilOut.timestamp = timestamp
        soilOut.theta = thetas
        soilOut.waterSuction = richards.outWaterSuctions
        soilOut.darcyVelocity = richards.outDarcyVelocity
        soilOut.errorVolume = richards.outErrorVolume
        soilOut.topBC = richards.outTopBCValue
        soilOut.bottomBC = richards.outBottomBCValue
        soilOut.write()

        brokerOut.timestamp = timestamp
        brokerOut.stressedETs = broker.stressedETs[0..<pondIndex] as double[]
        brokerOut.writeStep()

        etOut.timestamp = timestamp
        etOut.evapoTranspiration = pt.evapoTranspirationPT
        etOut.write()
    }
}


////////////////////////////////////
// PLOT RESULTS
////////////////////////////////////
def timesArray = times as long[]
def chart = new StackedTimeSeries("Summary chart")
def rainPanel = chart.addPanel("rain [mm/h]").addSeries("rain and irrigation", timesArray, rainValues as double[])
rainPanel.asBars = true
rainPanel.inverted = true
chart.addPanel("ET [mm/h]").addSeries("evapotranspiration", timesArray, etValues as double[])
chart.addPanel("stress [-]").addSeries("stress factor", timesArray, stressValues as double[])
def thetaPanel = chart.addPanel("theta [-]")
thetaPanel.weight = 3
watchDepths.eachWithIndex { depth, k ->
    thetaPanel.addSeries("theta at ${depth} m", timesArray, thetaValues[k] as double[])
}
chart.getImage(1100, 900)



////////////////////////////////////////////
// HELPER FUNCTIONS
////////////////////////////////////////////
def deleteGpkg(path){
    if ((path as File).exists()) {
        (path as File).delete()
    }
}