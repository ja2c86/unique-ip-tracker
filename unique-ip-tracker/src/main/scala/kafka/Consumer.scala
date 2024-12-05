package kafka

import cats.effect.kernel.Async
import cats.syntax.all.*
import fs2.kafka.{AutoOffsetReset, ConsumerRecord, ConsumerSettings, Deserializer, KafkaConsumer}
import io.circe.jawn.decodeByteArray
import model.DeviceEvent
import mongo.Repository
import org.typelevel.log4cats.LoggerFactory
import util.TimestampValidator

trait Consumer[F[_]] {
  def consume(): fs2.Stream[F, Unit]
}

object Consumer {
  def impl[F[_]: Async: LoggerFactory](repository: Repository[F]): Consumer[F] =
    new Consumer[F] {
      val logger = LoggerFactory[F].getLogger

      val BOOTSTRAP_SERVERS = "kafka:29092"
      val TOPIC = "ip_tracker_topic"
      val GROUP_ID = "ip_tracker_group"

      val valueDeserializer: Deserializer[F, DeviceEvent] =
        Deserializer.lift[F, DeviceEvent](byteArray => decodeByteArray[DeviceEvent](byteArray).liftTo[F])

      val consumerSettings: ConsumerSettings[F, String, DeviceEvent] =
        ConsumerSettings(
          keyDeserializer = Deserializer[F, String],
          valueDeserializer = valueDeserializer
        )
          .withAutoOffsetReset(AutoOffsetReset.Earliest)
          .withBootstrapServers(BOOTSTRAP_SERVERS)
          .withGroupId(GROUP_ID)

      def consume(): fs2.Stream[F, Unit] = {
        KafkaConsumer
          .stream(consumerSettings)
          .subscribeTo(TOPIC)
          .partitionedRecords
          .map { partitionStream =>
            partitionStream.evalMap { committable =>
              processRecord(committable.record)
                .recoverWith { error =>
                  logger.error(s"Error processing record: $error")
                }
            }
          }
          .parJoinUnbounded
      }

      private def processRecord(record: ConsumerRecord[String, DeviceEvent]): F[Unit] = {
        for {
          _ <- logger.info(s"Processing record: $record")
          _ <-
            TimestampValidator.parseTimestamp(record.value.timestamp) match {
              case Left(error) =>
                logger.error(s"Error processing record: $error")

              case Right(parsed) =>
                repository.existTrackedIp(record.value.device_ip).flatMap { exists =>
                  if (!exists) {
                    repository.addTrackedIp(record.value.device_ip, parsed.toInstant)
                  } else {
                    Async[F].unit
                  }
                }
            }
        } yield ()
      }
    }
}
