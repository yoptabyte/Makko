package io.markko.worker.services

import com.typesafe.scalalogging.LazyLogging
import io.prometheus.client.Counter

import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import scala.util.{Try, Success, Failure}

/**
 * Circuit Breaker for unreliable HTTP calls (fetching external pages).
 *
 * States: Closed → Open → Half-Open → Closed
 * - Closed: normal operation, track failures
 * - Open: all calls fail-fast, wait for reset timeout
 * - Half-Open: allow one probe call, success → Closed, failure → Open
 */
class CircuitBreaker(
  maxFailures:    Int = 5,
  resetTimeoutMs: Long = 30000,     // 30 seconds
  halfOpenMax:    Int = 1
) extends LazyLogging {

  sealed trait State
  case object Closed   extends State
  case object Open     extends State
  case object HalfOpen extends State

  @volatile private var state: State = Closed
  private val failureCount   = new AtomicInteger(0)
  private val lastFailureAt  = new AtomicLong(0)
  private val halfOpenProbes = new AtomicInteger(0)

  // Prometheus metrics
  private val tripsTotal = Counter.build()
    .name("markko_circuit_breaker_trips_total")
    .help("Total number of circuit breaker state transitions to Open")
    .register()

  private val callsRejected = Counter.build()
    .name("markko_circuit_breaker_rejected_total")
    .help("Total number of calls rejected by open circuit breaker")
    .register()

  /**
   * Execute a block through the circuit breaker.
   * Returns Success(result) or Failure(exception).
   */
  def protect[T](block: => T): Try[T] = {
    state match {
      case Open =>
        // Check if we should transition to half-open
        if (System.currentTimeMillis() - lastFailureAt.get() > resetTimeoutMs) {
          logger.info("Circuit breaker transitioning to Half-Open")
          state = HalfOpen
          halfOpenProbes.set(0)
          tryCall(block)
        } else {
          callsRejected.inc()
          Failure(new CircuitBreakerOpenException(
            s"Circuit breaker is Open (${failureCount.get()} failures, " +
            s"resets in ${(resetTimeoutMs - (System.currentTimeMillis() - lastFailureAt.get())) / 1000}s)"
          ))
        }

      case HalfOpen =>
        if (halfOpenProbes.getAndIncrement() < halfOpenMax) {
          tryCall(block)
        } else {
          callsRejected.inc()
          Failure(new CircuitBreakerOpenException("Circuit breaker is Half-Open, max probes reached"))
        }

      case Closed =>
        tryCall(block)
    }
  }

  private def tryCall[T](block: => T): Try[T] = {
    Try(block) match {
      case s @ Success(_) =>
        onSuccess()
        s
      case f @ Failure(ex) =>
        onFailure(ex)
        f
    }
  }

  private def onSuccess(): Unit = {
    if (state == HalfOpen) {
      logger.info("Circuit breaker closing (successful probe)")
    }
    failureCount.set(0)
    state = Closed
  }

  private def onFailure(ex: Throwable): Unit = {
    val count = failureCount.incrementAndGet()
    lastFailureAt.set(System.currentTimeMillis())

    if (count >= maxFailures && state == Closed) {
      state = Open
      tripsTotal.inc()
      logger.warn(s"Circuit breaker tripped to Open after $count failures: ${ex.getMessage}")
    } else if (state == HalfOpen) {
      state = Open
      tripsTotal.inc()
      logger.warn(s"Circuit breaker back to Open (probe failed): ${ex.getMessage}")
    }
  }

  def currentState: State = state
  def failures: Int = failureCount.get()
}

class CircuitBreakerOpenException(msg: String) extends RuntimeException(msg)
