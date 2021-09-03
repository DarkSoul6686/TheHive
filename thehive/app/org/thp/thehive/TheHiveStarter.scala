package org.thp.thehive

import org.thp.scalligraph.ScalligraphApplicationImpl
import play.api._
import play.api.libs.concurrent.ActorSystemProvider.ApplicationShutdownReason
import play.core.server.{RealServerProcess, ServerConfig, ServerProcess, ServerProvider}

import java.io.File

object TheHiveStarter extends App {

  lazy val logger: Logger = Logger(getClass)
  val mode: Mode          = if (args.contains("--dev")) Mode.Dev else Mode.Prod
  startService(mode)

  def startService(mode: Mode): Unit = {
    val process = new RealServerProcess(args.toSeq)
    val config  = readConfig(process)

    val application = {
      val scalligraphApplication = new ScalligraphApplicationImpl(config.rootDir, process.classLoader, mode)
      try {
        scalligraphApplication.initializeLogger()
        scalligraphApplication.loadModules()
        val playModules      = scalligraphApplication.configuration.getOptional[Seq[String]]("play.modules.enabled").getOrElse(Nil)
        val loadMispModule   = playModules.contains("org.thp.thehive.connector.misp.MispModule")
        val loadCortexModule = playModules.contains("org.thp.thehive.connector.cortex.CortexModule")
        if (loadMispModule || loadCortexModule)
          logger.warn("play.modules.enabled is deprecated in application.conf, use scalligraph.modules")
        if (loadMispModule)
          scalligraphApplication.loadModule("org.thp.thehive.connector.misp.MispModule").foreach(_.init())
        if (loadCortexModule)
          scalligraphApplication.loadModule("org.thp.thehive.connector.cortex.CortexModule").foreach(_.init())

        scalligraphApplication.initModules()
        scalligraphApplication.application
      } catch {
        case e: Throwable =>
          logger.error("TheHive startup failure", e)
          scalligraphApplication.coordinatedShutdown.run(ApplicationShutdownReason).map(_ => System.exit(1))(scalligraphApplication.executionContext)
          throw e
      }
    }
    Play.start(application)

    // Start the server
    val serverProvider = ServerProvider.fromConfiguration(process.classLoader, config.configuration)
    val server         = serverProvider.createServer(config, application)

    process.addShutdownHook {
      if (application.coordinatedShutdown.shutdownReason().isEmpty)
        server.stop()
    }
  }

  def readConfig(process: ServerProcess) = {
    val configuration: Configuration = {
      val rootDirArg    = process.args.headOption.map(new File(_))
      val rootDirConfig = rootDirArg.fold(Map.empty[String, String])(ServerConfig.rootDirConfig)
      Configuration.load(process.classLoader, process.properties, rootDirConfig, allowMissingApplicationConf = true)
    }
    val rootDir: File = {
      val path = configuration
        .getOptional[String]("play.server.dir")
        .getOrElse(sys.error("No root server path supplied"))
      val file = new File(path)
      if (!file.isDirectory)
        sys.error(s"Bad root server path: $path")
      file
    }

    def parsePort(portType: String): Option[Int] =
      configuration.getOptional[String](s"play.server.$portType.port").filter(_ != "disabled").map { str =>
        try Integer.parseInt(str)
        catch {
          case _: NumberFormatException =>
            sys.error(s"Invalid ${portType.toUpperCase} port: $str")
        }
      }

    val httpPort  = parsePort("http")
    val httpsPort = parsePort("https")
    val address   = configuration.getOptional[String]("play.server.http.address").getOrElse("0.0.0.0")

    if (httpPort.orElse(httpsPort).isEmpty)
      sys.error("Must provide either an HTTP or HTTPS port")

    ServerConfig(rootDir, httpPort, httpsPort, address, Mode.Dev, process.properties, configuration)
  }
}