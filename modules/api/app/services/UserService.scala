package services

import auth.AuthUser
import cats.effect.unsafe.implicits.global
import javax.inject.{Inject, Singleton}
import play.silhouette.api.LoginInfo
import play.silhouette.api.services.IdentityService
import repositories.UserRepository

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class UserService @Inject()(
  userRepository: UserRepository
)(implicit ec: ExecutionContext) extends IdentityService[AuthUser] {

  override def retrieve(loginInfo: LoginInfo): Future[Option[AuthUser]] =
    userRepository.findByEmail(loginInfo.providerKey).unsafeToFuture().map(_.map(AuthUser(_)))

  def findById(id: Long): Future[Option[AuthUser]] =
    userRepository.findById(id).unsafeToFuture().map(_.map(AuthUser(_)))
}
