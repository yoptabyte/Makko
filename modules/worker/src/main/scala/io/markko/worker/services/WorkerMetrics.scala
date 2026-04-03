package io.markko.worker.services

import io.prometheus.client.{Counter, Gauge, Histogram}

/**
 * Centralized Prometheus metrics registry for the Markko worker.
 * All metrics are registered once here and referenced throughout the codebase.
 */
object WorkerMetrics {

  // ==================== Parse Pipeline ====================

  val parseJobsStarted: Counter = Counter.build()
    .name("markko_parse_jobs_started_total")
    .help("Total parse jobs started")
    .register()

  val parseJobsCompleted: Counter = Counter.build()
    .name("markko_parse_jobs_completed_total")
    .help("Total parse jobs completed successfully")
    .register()

  val parseJobsFailed: Counter = Counter.build()
    .name("markko_parse_jobs_failed_total")
    .help("Total parse jobs failed")
    .register()

  val parseJobsTotal: Counter = Counter.build()
    .name("markko_parse_jobs_total")
    .help("Total parse jobs processed")
    .labelNames("status") // success, failure
    .register()

  val parseLatency: Histogram = Histogram.build()
    .name("markko_parse_latency_seconds")
    .help("Parse job latency in seconds")
    .buckets(1, 5, 10, 30, 60, 120)
    .register()

  val parseQueueDepth: Gauge = Gauge.build()
    .name("markko_parse_queue_depth")
    .help("Current depth of the parse queue in Redis")
    .register()

  // ==================== Export Pipeline ====================

  val exportJobsStarted: Counter = Counter.build()
    .name("markko_export_jobs_started_total")
    .help("Total export jobs started")
    .register()

  val exportJobsCompleted: Counter = Counter.build()
    .name("markko_export_jobs_completed_total")
    .help("Total export jobs completed successfully")
    .register()

  val exportJobsFailed: Counter = Counter.build()
    .name("markko_export_jobs_failed_total")
    .help("Total export jobs failed")
    .register()

  val exportJobsTotal: Counter = Counter.build()
    .name("markko_export_jobs_total")
    .help("Total export jobs processed")
    .labelNames("status") // success, failure
    .register()

  val exportsSuccessTotal: Counter = Counter.build()
    .name("markko_exports_success_total")
    .help("Total successful exports to Obsidian vault")
    .register()

  val exportsFailedTotal: Counter = Counter.build()
    .name("markko_exports_failed_total")
    .help("Total failed exports")
    .register()

  // ==================== Links ====================

  val linksCreatedTotal: Counter = Counter.build()
    .name("markko_links_created_total")
    .help("Total links added to the system")
    .register()

  val linksTotal: Gauge = Gauge.build()
    .name("markko_links_total")
    .help("Total number of links in the database")
    .register()

  val esIndexedTotal: Gauge = Gauge.build()
    .name("markko_es_indexed_total")
    .help("Total number of links indexed in Elasticsearch")
    .register()

  // ==================== Parse Failures ====================

  val parseFailuresTotal: Counter = Counter.build()
    .name("markko_parse_failures_total")
    .help("Total parse failures by type")
    .labelNames("type") // fetch, convert, index, export
    .register()

  // ==================== Worker Health ====================

  val workerUptime: Gauge = Gauge.build()
    .name("markko_worker_uptime_seconds")
    .help("Worker uptime in seconds")
    .register()

  val activeWorkers: Gauge = Gauge.build()
    .name("markko_active_workers")
    .help("Number of active worker nodes in the cluster")
    .register()
}
