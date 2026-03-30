package io.markko.shared.domain

/** A tag that can be attached to links */
case class Tag(
  id:   Long,
  name: String
)

/** Join table entry */
case class LinkTag(
  linkId: Long,
  tagId:  Long
)
