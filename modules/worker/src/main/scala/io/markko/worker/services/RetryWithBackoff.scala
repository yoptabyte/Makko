package io.markko.worker.services

import com.typesafe.scalalogging.LazyLogging
import io.prometheus.client.Counter

import scala.util.{Try, Success, Failure}

/**
 * Retry logic with exponential backoff for unreliable operations.
 * Works with the CircuitBreaker for comprehensive fault tolerance.
 *
 * backoff = baseDelay * 2^attempt + jitter
 */
object RetryWithBackoff extends LazyLogging {

  private val retriesTotal = Counter.build()
    .name("markko_retries_total")
    .help("Total number of retry attempts")
    .register()

  /**
   * Execute with exponential backoff retries.
   *
   * @param maxRetries   Maximum number of retry attempts
   * @param baseDelayMs  Base delay in milliseconds (doubles each attempt)
   * @param maxDelayMs   Maximum delay cap
   * @param label        Label for logging
   * @param block        The operation to execute
   */
  def execute[T](
    maxRetries:  Int  = 3,
    baseDelayMs: Long = 1000,
    maxDelayMs:  Long = 30000,
    label:       String = "operation"
  )(block: => T): Try[T] = {
    var attempt = 0
    var lastError: Throwable = null

    while (attempt <= maxRetries) {
      Try(block) match {
        case s @ Success(_) =>
          if (attempt > 0) {
            logger.info(s"$label succeeded on attempt ${attempt + 1}")
          }
          return s
        case Failure(ex) =>
          lastError = ex
          if (attempt < maxRetries) {
            val delay = calculateDelay(attempt, baseDelayMs, maxDelayMs)
            logger.warn(s"$label failed (attempt ${attempt + 1}/$maxRetries), retrying in ${delay}ms: ${ex.getMessage}")
            retriesTotal.inc()
            Thread.sleep(delay)
          } else {
            logger.error(s"$label failed after ${maxRetries + 1} attempts: ${ex.getMessage}")
          }
      }
      attempt += 1
    }

    Failure(lastError)
  }

  /**
   * Execute with both Circuit Breaker and exponential backoff.
   */
  def executeWithBreaker[T](
    breaker:     CircuitBreaker,
    maxRetries:  Int  = 3,
    baseDelayMs: Long = 1000,
    maxDelayMs:  Long = 30000,
    label:       String = "operation"
  )(block: => T): Try[T] = {
    var attempt = 0
    var lastError: Throwable = null

    while (attempt <= maxRetries) {
      breaker.protect(block) match {
        case s @ Success(_) =>
          return s
        case Failure(ex: CircuitBreakerOpenException) =>
          // Don't retry if circuit is open — fail fast
          logger.warn(s"$label circuit breaker open, failing fast: ${ex.getMessage}")
          return Failure(ex)
        case Failure(ex) =>
          lastError = ex
          if (attempt < maxRetries) {
            val delay = calculateDelay(attempt, baseDelayMs, maxDelayMs)
            logger.warn(s"$label failed (attempt ${attempt + 1}/$maxRetries), retrying in ${delay}ms: ${ex.getMessage}")
            retriesTotal.inc()
            Thread.sleep(delay)
          }
      }
      attempt += 1
    }

    Failure(lastError)
  }

  /** Calculate delay with exponential backoff + jitter */
  private def calculateDelay(attempt: Int, baseDelayMs: Long, maxDelayMs: Long): Long = {
    val exponential = baseDelayMs * Math.pow(2, attempt).toLong
    val jitter      = (Math.random() * baseDelayMs * 0.5).toLong
    Math.min(exponential + jitter, maxDelayMs)
  }
}
