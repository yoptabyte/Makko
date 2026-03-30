package io.markko.shared.domain

import java.time.Instant

/** Status of a link through the parsing pipeline */
sealed trait LinkStatus
object LinkStatus {
  case object Pending  extends LinkStatus
  case object Parsing  extends LinkStatus
  case object Parsed   extends LinkStatus
  case object Failed   extends LinkStatus

  def fromString(s: String): LinkStatus = s.toLowerCase match {
    case "pending"  => Pending
    case "parsing"  => Parsing
    case "parsed"   => Parsed
    case "failed"   => Failed
    case other      => throw new IllegalArgumentException(s"Unknown LinkStatus: $other")
  }

  def asString(s: LinkStatus): String = s match {
    case Pending  => "pending"
    case Parsing  => "parsing"
    case Parsed   => "parsed"
    case Failed   => "failed"
  }
}

/** A saved bookmark link */
case class Link(
  id:             Long,
  url:            String,
  urlHash:        String,
  title:          Option[String],
  contentMd:      Option[String],
  readingTimeMin: Option[Int],
  status:         LinkStatus,
  collectionId:   Option[Long],
  exportDirectory: Option[String],
  exportFileName:  Option[String],
  savedAt:        Instant,
  parsedAt:       Option[Instant],
  indexedAt:      Option[Instant]
)

/** Request to create a new link */
case class NewLink(
  url:             String,
  tags:            List[String],
  collectionId:    Option[Long],
  exportDirectory: Option[String],
  exportFileName:  Option[String]
)

/** A link with its associated tags (for API responses) */
case class LinkWithTags(
  link: Link,
  tags: List[Tag]
)
