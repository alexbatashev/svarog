package svarog.soc

case class SvarogConfig(
    xlen: Int = 32,
    memSizeBytes: Int = 0,
    clockHz: Int = 50_000_000,
    uartInitialBaud: Int = 115200,
    programEntryPoint: BigInt = BigInt("80000000", 16)
)
