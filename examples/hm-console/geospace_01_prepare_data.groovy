import org.hortonmachine.gears.utils.math.interpolation.LinearListInterpolator
import org.hortonmachine.HM
import org.geoframe.whetgeo1d.io.Whetgeo1DInputsBuilder
import org.geoframe.geoet.io.GeoetInputsBuilder
import org.geoframe.geoet.io.GeoetInputsHandler
import org.hortonmachine.gears.utils.chart.Scatter
import org.geoframe.whetgeo1d.io.Whetgeo1DInputsHandler

//   A time span from May 10 to June 29 2018.
//   Rain and irrigation enter a 2.5 m soil column, the grass roots take
//   water out of it, the rest drains at the base. How much water goes
//   back to the atmosphere, when do the plants run short of water and
//   from which depths do the roots take it?

def workspace = "/home/hydrologis/storage/lavori_tmp/UNITN/WHETGEO1D/"
def dataFolder = workspace + "geospace_coupled/" as File
def gpkg = dataFolder.absolutePath + "/geospace_coupled.gpkg"

def dem = workspace + "data/geospace/DemSpikeIIcut.tif"
def centroid = workspace + "data/geospace/centroid.shp"

def precAndIrrig = workspace + "data/geospace/Prec_Irrig_Height_hourly.csv"
def airTemp = workspace + "data/geospace/AirTemperature_hourly.csv"
def solarRad = workspace + "data/geospace/Solar_Radiation_mean_hourly.csv"

def startDate = "2018-05-10 00:00"
def endDate = "2018-06-29 02:00"

def tableTopBC = "timeseries_topBC"
//def tableBotBC = "timeseries_bottomBC"

checkFolder(dataFolder)
deleteGpkg(gpkg)

////////////////////////////////////
// THE ROOTS
////////////////////////////////////
// roots reach 2 m deep. Their density, measured every 25 cm and
// interpolated linearly in between, is highest around half a meter.
def rootDepth = -2.0
def rootEtas = [0.0, -0.5, -0.75, -1.0, -1.25, -1.5, -1.75, -2.0]
def rootDensities = [0.112, 0.298, 0.209, 0.178, 0.180, 0.190, 0.201, 0.183]

// filled once the soil column exists: one value per soil cell, and the cell centres
def rootDensity
def soilEtas


////////////////////////////////////
// THE SITE ELEVATION
////////////////////////////////////
// read the DEM at the site centroid: GEOET needs it to estimate the air
// pressure, which was not measured
def coord = HM.readVector(centroid).first().defaultGeometry.centroid.coordinate
def elevation = HM.readHMRaster(dem).withCloseable { r -> r.getValue(coord) }
println "Site elevation: " + elevation + " m"

HM.connectGeopackage(gpkg).withCloseable{ db ->
    ////////////////////////////////////
    // THE SOIL COLUMN, for WHETGEO-1D
    ////////////////////////////////////
    def soilBuilder = new Whetgeo1DInputsBuilder(db)
        // 2 m of soil above 0.5 m of a soil with the same retention curve,
        // but different parameters (i.e. permeablity). All 1 cm cells.
        .layer(0.0, 200, 1, 1)
        .layer(-2.0, 50, 1, 2)
        .domainBottom(-2.5)
        // the pond on top of the soil, as in the real rain example
        .pseudoCell(0, 1, 1.0, "Surface ponding reservoir coupled via TOP_COUPLED")
        // hydrostatic start: water table at the base
        .initialCondition(0.0, -2.5, 293.15)
        .initialCondition(-2.5, 0.0, 293.15)
        // Van Genuchten parameters plus the wilting point thetaWP and the
        // field capacity thetaFC [-], which the roots need
        .parameterSet(thetaS: 0.3, thetaR: 0.05, n: 1.31, alpha: 6.0, thetaWP: 0.0803, thetaFC: 0.205,
                alphaSpecificStorage: 1.0e-07, betaSpecificStorage: 4.4e-10, Ks: 3.472222e-05)
        .parameterSet(thetaS: 0.3, thetaR: 0.05, n: 1.31, alpha: 6.0, thetaWP: 0.0803, thetaFC: 0.235,
                alphaSpecificStorage: 1.0e-07, betaSpecificStorage: 4.4e-10, Ks: 1.38e-07)
        .vanGenuchtenSlots()
        // rain plus irrigation [mm/h] at the top, nothing at the base (free drainage)
        .timeseriesFromCsv(tableTopBC, precAndIrrig)
        // free drainage: the flux at the base follows from the conductivity
        // of the bottom cell, no bottom boundary data is needed
    soilBuilder.write()
    
    // read the column back, to put the root profile on its cells
    def soilIn = new Whetgeo1DInputsHandler(db)
    soilIn.read()
    // interpolate the root densities on the cells
    rootDensity = soilIn.profileOnCells(rootEtas as double[], rootDensities as double[], 0.0)
    soilEtas = soilIn.eta[0..<rootDensity.length] as double[]

    ////////////////////////////////////
    // PLANTS AND ATMOSPHERE, for GEOET
    ////////////////////////////////////
    def etBuilder = new GeoetInputsBuilder(db)
        .parameters(
            timeStepMinutes: 60,
            rootsDepth: rootDepth,
            elevation: elevation,
            // Priestley-Taylor: ET = alpha * equilibrium evaporation
            priestleyTaylorAlpha: 1.26,
            // soil heat flux as a fraction of net radiation, by day and by night
            soilFluxParameterDay: 0.35,
            soilFluxParameterNight: 0.75,
            // Jarvis: only the water stress is on
            useRadiationStress: 0,
            useTemperatureStress: 0,
            useVDPStress: 0,
            useWaterStress: 1,
            // the radiation, temperature and vapour pressure deficit stress shapes, unused here
            jarvisAlpha: 0.005, theta: 0.9, VPD0: 5.0, Tl: -5.0, T0: 20.0, Th: 45.0)
        .rootDensity(rootDensity)
        .timeseriesFromCsv(GeoetInputsHandler.VAR_AIR_TEMPERATURE, airTemp)
        .timeseriesFromCsv(GeoetInputsHandler.VAR_NET_RADIATION, solarRad)
        // not measured: -9999 tells GEOET to estimate them
        .constantTimeseries(GeoetInputsHandler.VAR_SOIL_FLUX, startDate, endDate, 60, -9999.0)
        .constantTimeseries(GeoetInputsHandler.VAR_ATMOSPHERIC_PRESSURE, startDate, endDate, 60, -9999.0)
    etBuilder.write()
}

println "Input data written to: " + gpkg

////////////////////////////////////
// PLOT THE ROOTS
////////////////////////////////////
def chart = new Scatter("Root density")
chart.setXLabel("root density [-]")
chart.setYLabel("eta [m]")
chart.setSortByX(false)
chart.addSeries("per cell", rootDensity, soilEtas as double[])
chart.addSeries("measured", rootDensities as double[], rootEtas as double[])
chart.setShowLines([false, true])
chart.setShowShapes([true, true])
chart.addHorizontalAnnotation("soil 1 | soil 2", -2.0)
chart.getImage(600, 700)








////////////////////////////////////////////
// HELPER FUNCTIONS
////////////////////////////////////////////
def checkFolder(path){
    if (!(path as File).exists()) {
        path.mkdirs()
    }
}

def deleteGpkg(path){
    if ((path as File).exists()) {
        (path as File).delete()
    }
}
