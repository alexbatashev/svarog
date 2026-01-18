package svarog

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.TestSuite
import svsim.BackendSettingsModifications
import svsim.verilator.Backend

trait VerilatorWarningSilencer extends ChiselSim { self: TestSuite =>
  abstract override implicit def backendSettingsModifications
      : BackendSettingsModifications = { original =>
    val base = super.backendSettingsModifications.apply(original)
    base match {
      case settings: Backend.CompilationSettings =>
        val disabled = settings.disabledWarnings
        if (disabled.contains("WIDTHEXPAND")) settings
        else settings.withDisabledWarnings(disabled :+ "WIDTHEXPAND")
      case other => other
    }
  }
}
