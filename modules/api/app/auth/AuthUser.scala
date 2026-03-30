package auth

import io.markko.shared.domain.User
import play.silhouette.api.Identity

final case class AuthUser(user: User) extends Identity {
  def id: Option[Long] = user.id
  def email: String = user.email
  def name: String = user.name
}
