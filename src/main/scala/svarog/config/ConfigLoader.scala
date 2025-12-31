package svarog.config

import io.circe.yaml.parser
import scala.io.Source
import scala.util.Using

object ConfigLoader {
  /** Load SoC configuration from YAML file
    *
    * @param configPath
    *   Path to the YAML config file
    * @return
    *   Either error message or parsed SoCYaml config
    */
  def loadSoCConfig(configPath: String): Either[String, SoCYaml] = {
    for {
      content <- Using(Source.fromFile(configPath))(_.mkString).toEither
        .left.map(e => s"Failed to read config file: ${e.getMessage}")
      parsed <- parser.parse(content)
        .left.map(e => s"Failed to parse YAML: ${e.getMessage}")
      config <- parsed.as[SoCYaml](Config.socYamlDecoder)
        .left.map(e => s"Invalid YAML structure: ${e.getMessage}")
    } yield config
  }
}
