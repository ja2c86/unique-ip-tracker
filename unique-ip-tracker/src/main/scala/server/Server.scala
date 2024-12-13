package server

import cats.effect.Resource
import cats.effect.kernel.Async
import com.comcast.ip4s.{host, port}
import http.Routes
import kafka.Consumer
import model.Config
import mongo.Repository
import mongo4cats.client.MongoClient
import org.http4s.ember.server.EmberServerBuilder
import org.typelevel.log4cats.LoggerFactory

object Server {

  def stream[F[_]: Async: LoggerFactory](config: Config): Resource[F, fs2.Stream[F, Nothing]] = {
    val logger = LoggerFactory[F].getLogger

    MongoClient.fromConnectionString[F](config.mongoConfig.connectionString).map { client =>
      for {
        _ <- fs2.Stream.eval(logger.info(s"Mongo Client Initialized: $client"))

        repository = Repository.impl[F](client, config)
        consumer = Consumer.impl[F](repository, config)
        routes = Routes[F](repository)

        server <-
          fs2.Stream.eval(
            EmberServerBuilder
              .default[F]
              .withHost(host"0.0.0.0")
              .withPort(port"8080")
              .withHttpApp(routes.router.orNotFound)
              .build
              .useForever
          ).concurrently(
            consumer.consume()
          )
      } yield server
    }
  }

}
