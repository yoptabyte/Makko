package io.markko.shared.json

import io.circe._
import io.circe.generic.semiauto._
import io.markko.shared.domain._

import java.time.Instant
import java.time.format.DateTimeFormatter

/** Circe JSON codecs for all domain types */
object Codecs {

  // --- Instant ---
  implicit val instantEncoder: Encoder[Instant] =
    Encoder.encodeString.contramap(DateTimeFormatter.ISO_INSTANT.format)

  implicit val instantDecoder: Decoder[Instant] =
    Decoder.decodeString.emap { s =>
      try Right(Instant.parse(s))
      catch { case e: Exception => Left(s"Invalid instant: ${e.getMessage}") }
    }

  // --- LinkStatus ---
  implicit val linkStatusEncoder: Encoder[LinkStatus] =
    Encoder.encodeString.contramap(LinkStatus.asString)

  implicit val linkStatusDecoder: Decoder[LinkStatus] =
    Decoder.decodeString.emap { s =>
      try Right(LinkStatus.fromString(s))
      catch { case e: Exception => Left(e.getMessage) }
    }

  // --- ExportStatus ---
  implicit val exportStatusEncoder: Encoder[ExportStatus] =
    Encoder.encodeString.contramap(ExportStatus.asString)

  implicit val exportStatusDecoder: Decoder[ExportStatus] =
    Decoder.decodeString.emap { s =>
      try Right(ExportStatus.fromString(s))
      catch { case e: Exception => Left(e.getMessage) }
    }

  // --- Domain models ---
  implicit val tagEncoder: Encoder[Tag] = deriveEncoder[Tag]
  implicit val tagDecoder: Decoder[Tag] = deriveDecoder[Tag]

  implicit val linkEncoder: Encoder[Link] = deriveEncoder[Link]
  implicit val linkDecoder: Decoder[Link] = deriveDecoder[Link]

  implicit val linkWithTagsEncoder: Encoder[LinkWithTags] = deriveEncoder[LinkWithTags]
  implicit val linkWithTagsDecoder: Decoder[LinkWithTags] = deriveDecoder[LinkWithTags]

  implicit val newLinkEncoder: Encoder[NewLink] = deriveEncoder[NewLink]
  implicit val newLinkDecoder: Decoder[NewLink] = deriveDecoder[NewLink]

  implicit val collectionEncoder: Encoder[Collection] = deriveEncoder[Collection]
  implicit val collectionDecoder: Decoder[Collection] = deriveDecoder[Collection]

  implicit val newCollectionEncoder: Encoder[NewCollection] = deriveEncoder[NewCollection]
  implicit val newCollectionDecoder: Decoder[NewCollection] = deriveDecoder[NewCollection]

  implicit val exportJobEncoder: Encoder[ExportJob] = deriveEncoder[ExportJob]
  implicit val exportJobDecoder: Decoder[ExportJob] = deriveDecoder[ExportJob]

  implicit val parseResultEncoder: Encoder[ParseResult] = deriveEncoder[ParseResult]
  implicit val parseResultDecoder: Decoder[ParseResult] = deriveDecoder[ParseResult]

  implicit val parseJobEncoder: Encoder[ParseJob] = deriveEncoder[ParseJob]
  implicit val parseJobDecoder: Decoder[ParseJob] = deriveDecoder[ParseJob]

  implicit val linkTagEncoder: Encoder[LinkTag] = deriveEncoder[LinkTag]
  implicit val linkTagDecoder: Decoder[LinkTag] = deriveDecoder[LinkTag]
}
