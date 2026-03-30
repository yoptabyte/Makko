package repositories

import java.time.LocalDateTime
import javax.inject.{Inject, Singleton}
import cats.effect.IO
import doobie._
import doobie.implicits._
import io.markko.shared.domain.User

@Singleton
class UserRepository @Inject()(xa: Transactor[IO]) {

  private def toUser(row: (Long, String, String, Option[String], String, String)): User = {
    val (id, email, name, password, createdAt, updatedAt) = row
    User(
      id = Some(id),
      email = email,
      name = name,
      password = password,
      createdAt = Option(LocalDateTime.parse(createdAt)),
      updatedAt = Option(LocalDateTime.parse(updatedAt))
    )
  }
  
  def create(user: User): IO[Long] = {
    sql"""
      INSERT INTO users (email, name, password_hash, created_at, updated_at)
      VALUES (${user.email}, ${user.name}, ${user.password}, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
    """.update.withUniqueGeneratedKeys[Long]("id")
      .transact(xa)
  }
  
  def findById(id: Long): IO[Option[User]] = {
    sql"""
      SELECT
        id,
        email,
        name,
        password_hash,
        DATE_FORMAT(created_at, '%Y-%m-%dT%H:%i:%s'),
        DATE_FORMAT(updated_at, '%Y-%m-%dT%H:%i:%s')
      FROM users
      WHERE id = $id
    """.query[(Long, String, String, Option[String], String, String)].option.map(_.map(toUser))
      .transact(xa)
  }
  
  def findByEmail(email: String): IO[Option[User]] = {
    sql"""
      SELECT
        id,
        email,
        name,
        password_hash,
        DATE_FORMAT(created_at, '%Y-%m-%dT%H:%i:%s'),
        DATE_FORMAT(updated_at, '%Y-%m-%dT%H:%i:%s')
      FROM users
      WHERE email = $email
    """.query[(Long, String, String, Option[String], String, String)].option.map(_.map(toUser))
      .transact(xa)
  }
  
  def update(id: Long, user: User): IO[Int] = {
    sql"""
      UPDATE users
      SET email = ${user.email}, name = ${user.name}, password_hash = ${user.password}, updated_at = CURRENT_TIMESTAMP
      WHERE id = $id
    """.update.run
      .transact(xa)
  }

  def updatePassword(email: String, passwordHash: Option[String]): IO[Int] = {
    sql"""
      UPDATE users
      SET password_hash = $passwordHash, updated_at = CURRENT_TIMESTAMP
      WHERE email = $email
    """.update.run
      .transact(xa)
  }
  
  def delete(id: Long): IO[Int] = {
    sql"""
      DELETE FROM users
      WHERE id = $id
    """.update.run
      .transact(xa)
  }
  
  def existsByEmail(email: String): IO[Boolean] = {
    sql"""
      SELECT COUNT(*) > 0
      FROM users
      WHERE email = $email
    """.query[Boolean].unique
      .transact(xa)
  }
}
