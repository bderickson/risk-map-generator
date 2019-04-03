package map_generator

import com.sun.scenario.effect.impl.sw.java.JSWBlend_BLUEPeer
import geotrellis.vector._
import geotrellis.vector.io._
import geotrellis.raster._
import spray.json.DefaultJsonProtocol._
import geotrellis.vector.voronoi.VoronoiDiagram
import geotrellis.raster.rasterize.Rasterizer
import geotrellis.vector.triangulation.DelaunayTriangulation
import spray.json.RootJsonFormat

import scala.util.Random

sealed trait TerrainType
case object Land extends TerrainType
case object Water extends TerrainType
case object Mountain extends TerrainType

sealed trait Color { val code: Int }
case object White extends Color { val code = 0xFFFFFFFF }
case object Black extends Color { val code = 0x000000FF }
case object Green extends Color { val code = 0x336600FF }
case object Grey extends Color { val code = 0x808080FF }
case object Blue extends Color { val code = 0x004C99FF }

case class GameTile(id: Int, centerPoint: Point, var terrainType: TerrainType)
class GameTilePolygon(override val geom: Polygon, override val data: GameTile) extends Feature[Polygon, GameTile](geom, data)

case class GeneratorConfig(numPoints: Int, maxX: Int, maxY: Int, randomSeed: Option[Int], lloydIterations: Int = 2, waterRatio: Double = 0.25)
object GeneratorConfig {
  def small: GeneratorConfig = GeneratorConfig(100, 1024, 1024, None, 2, 0.1)
  def medium: GeneratorConfig = GeneratorConfig(200, 4096, 4096, None, 2, 0.1)
  def large: GeneratorConfig = GeneratorConfig(400, 8192, 8192, None)
}

case class Board(voronoi: VoronoiDiagram, features: Seq[GameTilePolygon], extent: RasterExtent)

class Generator(config: GeneratorConfig) {
  def writeGeoJson(json: String, filePath: String): Unit = {
    import java.io._
    val file = new File(filePath)
    val bw = new BufferedWriter(new FileWriter(file))
    bw.write(json)
    bw.close()
  }

  def writePng(board: Board, filePath: String): Unit = {
    val arrayTile = ArrayTile(Array.fill(board.extent.cols * board.extent.rows)(White.code),
      board.extent.cols, board.extent.rows)

    // Set color by terrain type
    for (f <- board.features) {
      Rasterizer.foreachCellByGeometry(f.geom, board.extent)((x, y) => {
        val color = f.data.terrainType match {
          case Land => Green
          case Water => Blue
          case Mountain => Grey
        }
        arrayTile.set(x, y, color.code)
      })
    }

    // And finally, set the polygon borders to black
    for (f <- board.features) {
      Rasterizer.foreachCellByGeometry(f.geom.exterior, board.extent)((x, y) => arrayTile.set(x, y, Black.code))
    }
    arrayTile.renderPng().write(filePath)
  }

  def randomPoints: List[Point] = {
    val rand: Random = config.randomSeed match {
      case Some(seed) => new Random(seed)
      case None => new Random()
    }
    List.fill(config.numPoints)(Point.apply(rand.nextDouble * config.maxX, rand.nextDouble * config.maxY))
  }

  // Create an initial board with random points within an extent. Create a voronoi diagram from that,
  // then optionally improve the points with Lloyd's method.
  def initialBoard(vectorExtent: Extent): Board = {
    val rasterExtent = RasterExtent(vectorExtent, config.maxX, config.maxY)
    val maxIterations = 1 + config.lloydIterations

    var points = randomPoints
    var board: Option[Board] = None
    for (iteration <- Range(0, maxIterations)) {
      val voronoi = VoronoiDiagram.apply(points, vectorExtent, debug = false)
      val features = voronoi.voronoiCellsWithPoints().zipWithIndex.map {
        case ((polygon, coordinate), index) => new GameTilePolygon(polygon, GameTile(index, coordinate, Land))
      }
      board = Some(Board(voronoi, features, rasterExtent))
      writePng(board.get, s"initial-$iteration.png")

      points = voronoi.voronoiCells().map(p => p.centroid).collect { case p: PointResult => p.geom }.toList
    }

    board match {
      case Some(b) => b
      case None => throw new IllegalStateException("Failed to create initial board")
    }
  }

  def generate {
    val extent = new Extent(0, 0, config.maxX, config.maxY)
    val board = initialBoard(extent)

    // Now to make some water. To start, all tiles bordering the edge are marked as water tiles.
    val border = extent.toPolygon().exterior
    for (f <- board.features) {
      f.geom & border match {
        case MultiLineResult(_) => f.data.terrainType = Water
        case LineResult(_) => f.data.terrainType = Water
        case NoResult => f.data.terrainType = Land
      }
    }
    writePng(board, "initial-water.png")

    // Then randomly pick N polygons bordering water tiles and make those water as well
    // Make the rest land tiles
    // Bucket land tiles by their distance to water
    // Those land tiles farthest from water are mountains
  }
}
