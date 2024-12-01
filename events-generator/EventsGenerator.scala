//> using scala "3.5.2"
//> using dep "com.github.fd4s::fs2-kafka::3.6.0"
//> using dep "io.circe::circe-core::0.14.10"
//> using dep "io.circe::circe-generic::0.14.10"
//> using dep "io.circe::circe-parser::0.14.10"

import cats.effect.{IO, IOApp}
import fs2.kafka.{producer, *}
import io.circe.{Codec, Decoder, Encoder}
import io.circe.syntax.*

import java.nio.charset.StandardCharsets.UTF_8
import java.time.{Instant, ZoneOffset}
import java.time.format.DateTimeFormatter
import java.util.UUID
import scala.concurrent.duration.*
import scala.util.Random

case class DeviceEvent(timestamp: String, device_ip: String, error_code: Int) derives Codec.AsObject

object Generator {
  val TOPIC = "ip_tracker_topic"

  val recordsGenerator: fs2.Stream[IO, ProducerRecords[String, DeviceEvent]] = {
    fs2.Stream.repeatEval {
      IO {
        ProducerRecords.one(generateRecord())
      }
    }
  }

  private def generateRecord(): ProducerRecord[String, DeviceEvent] =
    ProducerRecord(
      TOPIC,
      UUID.randomUUID().toString,
      DeviceEvent(
        generateTimestamp(),
        generateDeviceIp(),
        generateErrorCode()
      )
    )

  private def generateTimestamp(): String = {
    val TIMESTAMP_FORMATS: List[String] = List(
      "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
      "yyyy-MM-dd'T'HH:mm:ss.SSS",
      "yyyy-MM-dd'T'HH:mm:ss",
      "unix_millis",
      "unix_seconds"
    )

    val format = Random.shuffle(TIMESTAMP_FORMATS).head
    val now = Instant.now().atZone(ZoneOffset.UTC) // Current time in UTC

    format match {
      case "unix_millis" => (now.toInstant.toEpochMilli).toString
      case "unix_seconds" => (now.toInstant.getEpochSecond).toString
      case other =>
        val formatter = DateTimeFormatter.ofPattern(other).withZone(ZoneOffset.UTC)
        formatter.format(now)
    }
  }

  private def generateDeviceIp(): String = {
    (1 to 4)
      .map(_ => Random.between(0, 256))
      .mkString(".")
  }

  private def generateErrorCode(): Int = Random.between(0, 11)
}

object EventsGenerator extends IOApp.Simple {
  val BOOTSTRAP_SERVERS = "localhost:9092"

  val run: IO[Unit] = {

    val valueSerializer: Serializer[IO, DeviceEvent] = Serializer.lift[IO, DeviceEvent] { event =>
      IO.pure(event.asJson.noSpaces.getBytes(UTF_8))
    }

    val producerSettings =
      ProducerSettings(
        keySerializer = Serializer[IO, String],
        valueSerializer = valueSerializer
      )
        .withBootstrapServers(BOOTSTRAP_SERVERS)

    KafkaProducer
      .stream(producerSettings)
      .flatMap { producer =>
        Generator.recordsGenerator
          .through(KafkaProducer.pipe(producer))
      }
      .compile
      .drain
  }
}
