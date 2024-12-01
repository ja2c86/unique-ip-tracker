package http

import cats.effect.kernel.Async
import mongo.Repository
import cats.implicits.*
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.*
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router

case class Routes[F[_]: Async](repository: Repository[F]) extends Http4sDsl[F] {

  private val routes: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "count" =>
      Ok(
        repository.countTrackedIps.map(_.asJson)
      )
  }

  val router = Router("/" -> routes)
}
