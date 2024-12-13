package mongo

import cats.effect.kernel.Async
import cats.syntax.all.*
import io.circe.generic.auto.*
import model.{Config, TrackedIp}
import mongo4cats.circe.*
import mongo4cats.client.MongoClient
import mongo4cats.codecs.MongoCodecProvider
import mongo4cats.collection.MongoCollection
import mongo4cats.operations.{Filter, Index}
import org.typelevel.log4cats.LoggerFactory

import java.time.Instant

trait Repository[F[_]] {
  def addTrackedIp(ipAddress: String, timestamp: Instant): F[Unit]
  def existTrackedIp(ipAddress: String): F[Boolean]
  def countTrackedIps: F[Long]
}

object Repository {
  def impl[F[_]: Async: LoggerFactory](client: MongoClient[F], config: Config): Repository[F] =
    new Repository[F] {
      val logger = LoggerFactory[F].getLogger

      private def getCollection: F[MongoCollection[F, TrackedIp]] =
        for {
          database <- client.getDatabase(config.mongoConfig.database)
          collection <- database.getCollectionWithCodec[TrackedIp](config.mongoConfig.collection)
          _ <- collection.createIndex(Index.ascending("ipAddress"))
        } yield collection

      def addTrackedIp(ipAddress: String, timestamp: Instant): F[Unit] =
        for {
          collection <- getCollection
          newDoc = TrackedIp(ipAddress, timestamp)
          _ <- collection.insertOne(newDoc).void
        } yield ()

      def existTrackedIp(ipAddress: String): F[Boolean] =
        for {
          collection <- getCollection
          trackedIp <- collection.find(Filter.eq("ipAddress", ipAddress)).first
        } yield trackedIp.isDefined

      def countTrackedIps: F[Long] =
        for {
          collection <- getCollection
          count <- collection.count
        } yield count
    }
}
