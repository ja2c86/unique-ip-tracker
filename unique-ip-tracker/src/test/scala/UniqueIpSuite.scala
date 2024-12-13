import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.dimafeng.testcontainers.{KafkaContainer, MongoDBContainer}
import fs2.kafka.{KafkaProducer, ProducerRecord, ProducerRecords, ProducerSettings, Serializer}
import io.circe.Decoder
import io.circe.generic.auto.*
import io.circe.syntax.*
import model.{Config, DeviceEvent, KafkaConfig, MongoConfig, TrackedIp}
import mongo4cats.circe.*
import mongo4cats.client.MongoClient
import mongo4cats.codecs.MongoCodecProvider
import org.http4s.{EntityDecoder, Status}
import org.http4s.circe.*
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory
import server.Server
import weaver.IOSuite

import java.nio.charset.StandardCharsets.UTF_8
import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneOffset}
import scala.concurrent.duration.*
import scala.util.Random

object UniqueIpSuite extends IOSuite {

  val testEvents: List[(DeviceEvent, String)] = generateTestEvents()

  given logging: LoggerFactory[IO] = Slf4jFactory.create[IO]
  given intDecoder: EntityDecoder[IO, Int] = jsonOf[IO, Int]

  def initKafkaContainer: Resource[IO, KafkaContainer] =
    Resource.make(IO(KafkaContainer()))(container => IO(container.stop())).evalTap(container => IO(container.start()))

  def initMongoContainer: Resource[IO, MongoDBContainer] =
    Resource.make(IO(MongoDBContainer()))(container => IO(container.stop())).evalTap(container => IO(container.start()))

  def initMongoClient(connectionString: String): Resource[IO, MongoClient[IO]] =
    MongoClient.fromConnectionString[IO](connectionString)

  def initKafkaProducer(bootstrapServers: String): Resource[IO, KafkaProducer[IO, String, DeviceEvent]] = {
    val valueSerializer: Serializer[IO, DeviceEvent] = Serializer.lift[IO, DeviceEvent] { event =>
      IO.pure(event.asJson.noSpaces.getBytes(UTF_8))
    }

    val producerSettings: ProducerSettings[IO, String, DeviceEvent] =
      ProducerSettings(
        keySerializer = Serializer[IO, String],
        valueSerializer = valueSerializer
      )
      .withBootstrapServers(bootstrapServers)

    KafkaProducer.resource(producerSettings)
  }

  def initServer(connectionString: String, bootstrapServers: String): Resource[IO, fs2.Stream[IO, Nothing]] = {
    Server.stream[IO](
      Config(
        MongoConfig(connectionString, "unique_ip_tracker", "tracked_ips"),
        KafkaConfig(bootstrapServers, "ip_tracker_topic", "ip_tracker_group")
      )
    )
  }

  override type Res = (
    KafkaContainer,
    KafkaProducer[IO, String, DeviceEvent],
    MongoDBContainer,
    MongoClient[IO],
    Client[IO]
  )

  override def sharedResource: Resource[IO, Res] = {
    for {
      kafkaContainer <- initKafkaContainer
      kafkaProducer  <- initKafkaProducer(kafkaContainer.bootstrapServers)

      mongoContainer <- initMongoContainer
      mongoClient    <- initMongoClient(mongoContainer.replicaSetUrl)

      server         <- initServer(mongoContainer.replicaSetUrl, kafkaContainer.bootstrapServers)
      _              <- server.compile.drain.background

      httpClient     <- EmberClientBuilder.default[IO].build
    } yield (kafkaContainer, kafkaProducer, mongoContainer, mongoClient, httpClient)
  }

  test("Successful device events processing and timestamp validation") { resources =>
    val (kafkaContainer, kafkaProducer, mongoContainer, mongoClient, httpClient) = resources

    for {
      _          <- produceRecords(kafkaProducer)
      _          <- IO.sleep(2.seconds)  // allow consumer to process
      count      <- getCount(httpClient)
      trackedIps <- getRecords(mongoClient).map(_.toList)
    } yield {
      val validateTimestamps =
        testEvents
          .map { testEvent =>
            val (deviceEvent, expectedResult) = testEvent
            expect(findRecordTimestamp(trackedIps, deviceEvent.device_ip) == expectedResult)
          }
          .reduce(_ |+| _)  // combine all expectations

      expect(count == 14) and validateTimestamps
    }
  }

  private def produceRecords(kafkaProducer: KafkaProducer[IO, String, DeviceEvent]): IO[Unit] = {
    fs2.Stream.emits(testEvents)
      .map { case (deviceEvent, _) => deviceEvent }
      .evalMap { deviceEvent =>
        kafkaProducer.produce(
          ProducerRecords.one(
            ProducerRecord("ip_tracker_topic", "test-key", deviceEvent)
          )
        )
      }
      .compile
      .drain
  }

  private def getCount(httpClient: Client[IO]): IO[Int] = {
    val baseUrl = "http://localhost:8080"

    httpClient.get(s"$baseUrl/count") { response =>
      if (response.status == Status.Ok) {
        response.as[Int]
      } else {
        logging.getLogger.error(s"Unexpected count response: ${response.status}") *>
          IO.pure(-1)
      }
    }
  }

  private def getRecords(mongoClient: MongoClient[IO]): IO[Iterable[TrackedIp]] = {
    for {
      database   <- mongoClient.getDatabase("unique_ip_tracker")
      collection <- database.getCollectionWithCodec[TrackedIp]("tracked_ips")
      result     <- collection.find.all
    } yield result
  }

  private def findRecordTimestamp(records: List[TrackedIp], ipAddress: String): String = {
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")

    records.find(_.ipAddress == ipAddress).map { record =>
      formatter.format(record.timestamp.atOffset(ZoneOffset.UTC))
    }.getOrElse("")
  }

  private def generateTestEvents(): List[(DeviceEvent, String)] = {
    def generateDeviceIp(): String = {
      (1 to 4)
        .map(_ => Random.between(0, 256))
        .mkString(".")
    }

    def generateErrorCode(): Int = Random.between(0, 11)

    List(
      (DeviceEvent("2021-12-03T16:15:30.235Z", generateDeviceIp(), generateErrorCode()), "2021-12-03T16:15:30.235Z"),
      (DeviceEvent("2021-12-03T16:15:30.235", generateDeviceIp(), generateErrorCode()), "2021-12-03T16:15:30.235Z"),
      (DeviceEvent("2021-10-28T00:00:00.000", generateDeviceIp(), generateErrorCode()), "2021-10-28T00:00:00.000Z"),
      (DeviceEvent("2011-12-03T10:15:30", generateDeviceIp(), generateErrorCode()), "2011-12-03T10:15:30.000Z"),
      (DeviceEvent("1726668850124", generateDeviceIp(), generateErrorCode()), "2024-09-18T14:14:10.124Z"),
      (DeviceEvent("1726667942", generateDeviceIp(), generateErrorCode()), "2024-09-18T13:59:02.000Z"),
      (DeviceEvent("969286895000", generateDeviceIp(), generateErrorCode()), "2000-09-18T14:21:35.000Z"),
      (DeviceEvent("3336042095", generateDeviceIp(), generateErrorCode()), "2075-09-18T14:21:35.000Z"),
      (DeviceEvent("3336042095000", generateDeviceIp(), generateErrorCode()), "2075-09-18T14:21:35.000Z"),
      (DeviceEvent("2024-11-28T15:30:00", generateDeviceIp(), generateErrorCode()), "2024-11-28T15:30:00.000Z"),
      (DeviceEvent("2024-11-28T15:30:00Z", generateDeviceIp(), generateErrorCode()), "2024-11-28T15:30:00.000Z"),
      (DeviceEvent("2024-11-28T15:30:00+01:00", generateDeviceIp(), generateErrorCode()), "2024-11-28T15:30:00.000Z"),
      (DeviceEvent("2024-W48-4T15:30:00", generateDeviceIp(), generateErrorCode()), ""),
      (DeviceEvent("2024-333T15:30:00", generateDeviceIp(), generateErrorCode()), ""),
      (DeviceEvent("2024-11-28T15:30:00+01:00[Europe/Paris]", generateDeviceIp(), generateErrorCode()), "2024-11-28T15:30:00.000Z"),
      (DeviceEvent("2024-11-28T15:30:00+02:00[America/New_York]", generateDeviceIp(), generateErrorCode()), "2024-11-28T08:30:00.000Z")
    )
  }
}
