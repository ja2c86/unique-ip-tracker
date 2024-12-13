import cats.effect.*
import model.{Config, KafkaConfig, MongoConfig}
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory
import server.Server

object Main extends IOApp.Simple {

  given logging: LoggerFactory[IO] = Slf4jFactory.create[IO]

  private def initConfig(): IO[Config] = {
    IO(
      Config(
        MongoConfig("mongodb://root:root@mongo:27017", "unique_ip_tracker", "tracked_ips"),
        KafkaConfig("kafka:29092", "ip_tracker_topic", "ip_tracker_group")
      )
    )
  }

  def run: IO[Unit] = {
    for {
      _ <- logging.getLogger.info(s"Starting Server")

      config <- initConfig()

      _ <- Server.stream[IO](config).use { serverStream =>
        serverStream.compile.drain
      }
    } yield ()
  }
}
