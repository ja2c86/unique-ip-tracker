import cats.effect.*
import cats.implicits.*
import com.comcast.ip4s.{host, port}
import http.Routes
import kafka.Consumer
import mongo.Repository
import mongo4cats.client.MongoClient
import org.http4s.ember.server.EmberServerBuilder
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory

object Main extends IOApp.Simple {

  given logging: LoggerFactory[IO] = Slf4jFactory.create[IO]

  def run: IO[Unit] = {
    for {
      _ <- logging.getLogger.info(s"Starting Server")

      _ <-
        MongoClient.fromConnectionString[IO]("mongodb://root:root@mongo:27017").use { client =>
          val server: fs2.Stream[IO, Nothing] = for {
            _ <- fs2.Stream.eval(logging.getLogger.info(s"Mongo Client Initialized: $client"))

            repository = Repository.impl[IO](client)
            consumer = Consumer.impl[IO](repository)
            routes = Routes[IO](repository)

            server <-
              fs2.Stream.eval(
                EmberServerBuilder
                  .default[IO]
                  .withHost(host"0.0.0.0")
                  .withPort(port"8080")
                  .withHttpApp(routes.router.orNotFound)
                  .build
                  .useForever
              ).concurrently(
                consumer.consume()
              )
          } yield server

          server.compile.drain
        }
    } yield ()
  }
}
