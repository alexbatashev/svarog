package svarog.config

/**
 * Tunable parameters for simulation-oriented CPU builds.
 */
final case class CpuSimConfig(
  xlen: Int = 32,
  dataMemBytes: Int = 4096,
  dataInitFile: Option[String] = None
)
