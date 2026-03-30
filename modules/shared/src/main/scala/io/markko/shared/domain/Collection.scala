package io.markko.shared.domain

import java.time.Instant

/** A named group of links */
case class Collection(
  id:        Long,
  name:      String,
  slug:      String,
  createdAt: Instant
)

case class NewCollection(
  name: String
)
