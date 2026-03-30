package io.markko.shared.domain

import java.time.Instant

/** Job sent to Pekko workers for parsing */
case class ParseJob(
  linkId: Long,
  url:    String
)

/** Status of an export to Obsidian vault */
sealed trait ExportStatus
object ExportStatus {
  case object Pending   extends ExportStatus
  case object Exporting extends ExportStatus
  case object Done      extends ExportStatus
  case object Failed    extends ExportStatus

  def fromString(s: String): ExportStatus = s.toLowerCase match {
    case "pending"   => Pending
    case "exporting" => Exporting
    case "done"      => Done
    case "failed"    => Failed
    case other       => throw new IllegalArgumentException(s"Unknown ExportStatus: $other")
  }

  def asString(s: ExportStatus): String = s match {
    case Pending   => "pending"
    case Exporting => "exporting"
    case Done      => "done"
    case Failed    => "failed"
  }
}

/** An export job that writes a link to the Obsidian vault */
case class ExportJob(
  id:          Long,
  linkId:      Long,
  status:      ExportStatus,
  vaultPath:   Option[String],
  errorMsg:    Option[String],
  createdAt:   Instant,
  completedAt: Option[Instant]
)

/** Result from a completed parse */
case class ParseResult(
  linkId:      Long,
  title:       String,
  contentMd:   String,
  readingTime: Int,
  imageCount:  Int
)
