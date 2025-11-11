package svarog.soc

case class SvarogConfig(
    xlen: Int = 32,
    memSizeBytes: Int = 0,
    clockHz: Int = 50_000_000,
    uartInitialBaud: Int = 115200,
    programEntryPoint: Long = 0x80000000L,
    enableDebugInterface: Boolean = false
)
