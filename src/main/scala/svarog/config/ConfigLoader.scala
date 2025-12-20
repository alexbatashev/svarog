package svarog.config

import io.circe.yaml.parser
import svarog.SvarogConfig
import scala.io.Source
import scala.util.Using

object ConfigLoader {
  def loadSvarogConfig(
      configPath: String,
      bootloaderPath: Option[String]
  ): Either[String, SvarogConfig] = {
    for {
      content <- Using(Source.fromFile(configPath))(_.mkString).toEither
        .left.map(e => s"Failed to read config file: ${e.getMessage}")
      parsed <- parser.parse(content)
        .left.map(e => s"Failed to parse YAML: ${e.getMessage}")
      config <- parsed.as[SvarogConfig]
        .left.map(e => s"Invalid YAML structure: ${e.getMessage}")
      configWithBootloader = config.copy(bootloader = bootloaderPath)
      _ <- configWithBootloader.validate()
    } yield configWithBootloader
  }
}
