package util

import java.time.{Instant, LocalDateTime, OffsetDateTime, ZoneOffset, ZonedDateTime}
import scala.util.Try

object TimestampValidator {

  def parseTimestamp(timestamp: String): Either[String, ZonedDateTime] = {
    if (isUnixTimestamp(timestamp)) {
      parseUnixTimestamp(timestamp)
        .toRight(s"Timestamp $timestamp can't be processed")
    } else {
      parseZoneDateTime(timestamp)
        .orElse(parseOffsetDateTime(timestamp))
        .orElse(parseLocalDateTime(timestamp))
        .toRight(s"Timestamp $timestamp can't be processed")
    }
  }

  private def parseUnixTimestamp(timestamp: String): Option[ZonedDateTime] = {
    Try {
      // Allow to identify EpochSecond between Apr 1973 until Oct 5138
      if (timestamp.length < 12) {
        Instant.ofEpochSecond(timestamp.toLong)
      } else {
        Instant.ofEpochMilli(timestamp.toLong)
      }
    }.toOption.map { instant =>
      instant.atZone(ZoneOffset.UTC)
    }
  }

  private def isUnixTimestamp(timestamp: String): Boolean = {
    val timestampPattern = "^[-+]?\\d+$".r

    timestamp match {
      case timestampPattern(_*) => true
      case _ => false
    }
  }

  private def parseZoneDateTime(date: String): Option[ZonedDateTime] = {
    Try(ZonedDateTime.parse(date)).toOption.map { zonedDateTime =>
      zonedDateTime.toLocalDateTime.atZone(ZoneOffset.UTC)
    }
  }

  private def parseOffsetDateTime(date: String): Option[ZonedDateTime] = {
    Try(OffsetDateTime.parse(date)).toOption.map { offsetDateTime =>
      offsetDateTime.toLocalDateTime.atZone(ZoneOffset.UTC)
    }
  }

  private def parseLocalDateTime(date: String): Option[ZonedDateTime] = {
    Try(LocalDateTime.parse(date)).toOption.map { localDateTime =>
      localDateTime.atZone(ZoneOffset.UTC)
    }
  }
}
