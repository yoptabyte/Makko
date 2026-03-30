package daos

import cats.effect.unsafe.implicits.global
import javax.inject.{Inject, Singleton}
import play.silhouette.api.LoginInfo
import play.silhouette.api.util.PasswordInfo
import play.silhouette.password.BCryptSha256PasswordHasher
import play.silhouette.persistence.daos.DelegableAuthInfoDAO
import repositories.UserRepository

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag

@Singleton
class PasswordInfoDAO @Inject()(
  userRepository: UserRepository
)(implicit ec: ExecutionContext) extends DelegableAuthInfoDAO[PasswordInfo] {

  override val classTag: ClassTag[PasswordInfo] = scala.reflect.classTag[PasswordInfo]

  override def find(loginInfo: LoginInfo): Future[Option[PasswordInfo]] =
    userRepository.findByEmail(loginInfo.providerKey).unsafeToFuture().map {
      _.flatMap(_.password.map(hash => PasswordInfo(BCryptSha256PasswordHasher.ID, hash)))
    }

  override def add(loginInfo: LoginInfo, authInfo: PasswordInfo): Future[PasswordInfo] =
    persist(loginInfo, authInfo)

  override def update(loginInfo: LoginInfo, authInfo: PasswordInfo): Future[PasswordInfo] =
    persist(loginInfo, authInfo)

  override def save(loginInfo: LoginInfo, authInfo: PasswordInfo): Future[PasswordInfo] =
    persist(loginInfo, authInfo)

  override def remove(loginInfo: LoginInfo): Future[Unit] =
    userRepository.updatePassword(loginInfo.providerKey, None).unsafeToFuture().map(_ => ())

  private def persist(loginInfo: LoginInfo, authInfo: PasswordInfo): Future[PasswordInfo] =
    userRepository.updatePassword(loginInfo.providerKey, Some(authInfo.password)).unsafeToFuture().map(_ => authInfo)
}
