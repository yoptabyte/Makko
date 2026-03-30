package services

import javax.inject.{Inject, Singleton}
import at.favre.lib.crypto.bcrypt.BCrypt

@Singleton
class PasswordService @Inject()() {
  
  private val cost = 12 // BCrypt cost factor
  
  def hash(password: String): String = {
    BCrypt.withDefaults().hashToString(cost, password.toCharArray)
  }
  
  def verify(password: String, hash: String): Boolean = {
    BCrypt.verifyer().verify(password.toCharArray, hash).verified
  }
}
