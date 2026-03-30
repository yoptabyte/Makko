package modules

import com.google.inject.{AbstractModule, Provides}
import daos.PasswordInfoDAO
import javax.inject.Singleton
import play.silhouette.api.repositories.AuthInfoRepository
import play.silhouette.api.services.AuthenticatorService
import play.silhouette.api.util.{Clock, IDGenerator, PasswordHasherRegistry}
import play.silhouette.impl.authenticators.{JWTAuthenticator, JWTAuthenticatorService}
import play.silhouette.impl.providers.CredentialsProvider
import play.silhouette.impl.util.SecureRandomIDGenerator
import play.silhouette.password.BCryptSha256PasswordHasher
import play.silhouette.persistence.daos.DelegableAuthInfoDAO
import play.silhouette.persistence.repositories.DelegableAuthInfoRepository
import play.silhouette.api.util.PasswordInfo
import services.JwtService

import scala.concurrent.ExecutionContext

class SilhouetteModule extends AbstractModule {

  override def configure(): Unit = ()

  @Provides
  @Singleton
  def provideIdGenerator()(implicit ec: ExecutionContext): IDGenerator =
    new SecureRandomIDGenerator()

  @Provides
  @Singleton
  def provideClock(): Clock = Clock()

  @Provides
  @Singleton
  def providePasswordHasherRegistry(): PasswordHasherRegistry =
    PasswordHasherRegistry(new BCryptSha256PasswordHasher())

  @Provides
  @Singleton
  def providePasswordDAO(passwordInfoDAO: PasswordInfoDAO): DelegableAuthInfoDAO[PasswordInfo] =
    passwordInfoDAO

  @Provides
  @Singleton
  def provideAuthInfoRepository(
    passwordInfoDAO: DelegableAuthInfoDAO[PasswordInfo]
  )(implicit ec: ExecutionContext): AuthInfoRepository =
    new DelegableAuthInfoRepository(passwordInfoDAO)

  @Provides
  @Singleton
  def provideCredentialsProvider(
    authInfoRepository: AuthInfoRepository,
    passwordHasherRegistry: PasswordHasherRegistry
  )(implicit ec: ExecutionContext): CredentialsProvider =
    new CredentialsProvider(authInfoRepository, passwordHasherRegistry)

  @Provides
  @Singleton
  def provideAuthenticatorService(
    jwtService: JwtService,
    idGenerator: IDGenerator,
    clock: Clock
  )(implicit ec: ExecutionContext): AuthenticatorService[JWTAuthenticator] =
    new JWTAuthenticatorService(jwtService.settings, None, jwtService, idGenerator, clock)
}
