package svarog.config

import java.nio.file.{Files, Paths}

object BootloaderValidator {
  /** Validates bootloader file exists and returns its path */
  def validate(path: String): Either[String, String] = {
    if (!Files.exists(Paths.get(path))) {
      Left(s"Bootloader file not found: $path")
    } else {
      Right(path)
    }
  }
}
