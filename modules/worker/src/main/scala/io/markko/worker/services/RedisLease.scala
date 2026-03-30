package io.markko.worker.services

import cats.effect._
import cats.effect.unsafe.implicits.global
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.effect.Log.Stdout._
import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging
import io.markko.shared.redis.RedisConfigSupport

import scala.concurrent.duration._

/**
 * Redis-based distributed lease for worker crash recovery.
 * Uses SETNX (SET if Not eXists) to ensure only one worker processes a job at a time.
 *
 * If a worker crashes mid-parse, the lease expires automatically,
 * and the job becomes available for retry.
 *
 * Usage:
 *   val lease = new RedisLease(config)
 *   if (lease.acquire(linkId, ttl = 5.minutes)) {
 *     try { processLink(linkId) }
 *     finally { lease.release(linkId) }
 *   }
 */
class RedisLease(config: Config) extends LazyLogging {
  private val workerId = java.util.UUID.randomUUID().toString.take(8)

  private val redisResource = Redis[IO].utf8(RedisConfigSupport.connectionUri(config))

  /**
   * Acquire a lease for processing a link.
   * Returns true if the lease was acquired, false if another worker holds it.
   */
  def acquire(linkId: Long, ttl: FiniteDuration = 5.minutes): Boolean = {
    val key = s"lease:parse:$linkId"
    val value = s"$workerId:${System.currentTimeMillis()}"

    redisResource.use { redis =>
      redis.setNx(key, value).flatMap { acquired =>
        if (acquired) {
          // Set TTL for auto-expiry on crash
          redis.expire(key, ttl).as(true)
        } else {
          IO.pure(false)
        }
      }
    }.unsafeRunSync()
  }

  /**
   * Release a lease after successful processing.
   */
  def release(linkId: Long): Unit = {
    val key = s"lease:parse:$linkId"
    redisResource.use { redis =>
      redis.get(key).flatMap {
        case Some(v) if v.startsWith(workerId) =>
          // Only release if we own it
          redis.del(key).void
        case _ =>
          IO.unit
      }
    }.unsafeRunSync()
  }

  /**
   * Check if a lease is held for a link.
   */
  def isHeld(linkId: Long): Boolean = {
    val key = s"lease:parse:$linkId"
    redisResource.use { redis =>
      redis.exists(key)
    }.unsafeRunSync()
  }

  /**
   * Extend the lease TTL (for long-running parse jobs).
   */
  def extend(linkId: Long, ttl: FiniteDuration = 5.minutes): Boolean = {
    val key = s"lease:parse:$linkId"
    redisResource.use { redis =>
      redis.get(key).flatMap {
        case Some(v) if v.startsWith(workerId) =>
          redis.expire(key, ttl).as(true)
        case _ =>
          IO.pure(false)
      }
    }.unsafeRunSync()
  }
}
