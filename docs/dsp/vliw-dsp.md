# VLIW DSP Extension (prototype)

A small, self-contained Very Long Instruction Word (VLIW) DSP block for Svarog.
It is meant as a learning prototype and a thing to play with — not (yet) a
production extension wired into the scalar pipeline.

The whole idea of VLIW is **static, explicit parallelism**: instead of the
hardware figuring out at runtime which operations can run together, one wide
instruction word carries several independent operations (one per *lane*), and
they all execute in the same cycle. The scheduler (you, or a compiler) decides
what goes in each lane.

This prototype gives you 4 lanes, each able to do `add`, `sub` or `mul`, over a
private 16-entry register file.

## Files

| File | What it is |
|------|------------|
| `src/main/scala/svarog/dsp/DspIsa.scala`     | Encoding: params, op codes, lane bundle, and a Scala assembler (`DspAsm`). |
| `src/main/scala/svarog/dsp/DspRegFile.scala` | Multi-ported DSP register file. |
| `src/main/scala/svarog/dsp/VliwDsp.scala`    | The DSP block: decode word → run all lanes → write back. |
| `src/main/scala/svarog/dsp/GenerateVliwDsp.scala` | Standalone Verilog emitter. |
| `src/test/scala/svarog/dsp/VliwDspSpec.scala` | ChiselSim tests / worked examples. |

## Encoding

One bundle is `numLanes × 16` bits (64 bits with the default 4 lanes). Lanes are
packed **little-lane-first**: lane 0 sits in the least-significant 16 bits.

```
 bundle (64 bits, default config)
 ┌───────────┬───────────┬───────────┬───────────┐
 │  lane 3   │  lane 2   │  lane 1   │  lane 0   │
 │ [63:48]   │ [47:32]   │ [31:16]   │ [15:0]    │
 └───────────┴───────────┴───────────┴───────────┘
```

Each 16-bit lane:

```
 15      12 11       8 7        4 3        0
 ┌─────────┬──────────┬──────────┬──────────┐
 │   op    │    rd    │   rs1    │   rs2    │
 └─────────┴──────────┴──────────┴──────────┘
```

| Field | Bits | Meaning |
|-------|------|---------|
| `op`  | 4    | `0`=NOP, `1`=ADD, `2`=SUB, `3`=MUL (room left for more) |
| `rd`  | 4    | destination DSP register (0–15) |
| `rs1` | 4    | first source DSP register |
| `rs2` | 4    | second source DSP register |

Per lane: `rd = rs1 (op) rs2`. `MUL` keeps the low `xlen` bits of the product,
exactly like the core's scalar `MUL`. A `NOP` lane writes nothing.

## Execution semantics

- **Single cycle.** The block accepts one bundle per cycle and commits all lane
  writes on the next clock edge. `io.inst.ready` is always high.
- **Read before write.** Every lane reads its sources from the register state as
  it was at the *start* of the cycle, then all writes commit together. So a
  bundle can swap two registers in one shot, and lanes never see each other's
  results — that is the scheduler's job to account for. This is the classic
  VLIW contract.
- **Write conflicts.** If two lanes target the same `rd`, the **higher-numbered
  lane wins**. (Well-scheduled code avoids this; the rule just makes the
  hardware deterministic.)
- **Host port.** A separate `hostWrite*` / `hostRead*` interface lets the main
  core (or a test) preload operands and read results back. The host write has
  priority over lane writes.

## Playing with it

### Build a bundle in Scala

`DspAsm` is a tiny assembler. Lanes you omit become NOPs.

```scala
import svarog.dsp.DspAsm

// lane0: r3 = r1 + r2,  lane1: r4 = r1 - r2,  lane2: r5 = r1 * r2
val word = DspAsm.bundle(
  DspAsm.add(rd = 3, rs1 = 1, rs2 = 2),
  DspAsm.sub(rd = 4, rs1 = 1, rs2 = 2),
  DspAsm.mul(rd = 5, rs1 = 1, rs2 = 2)
)
```

### Run the tests / examples

```bash
./mill svarog.test.testOnly svarog.dsp.VliwDspSpec
```

The spec doubles as worked examples, including a two-tap FIR-style computation
(`y = c0*x0 + c1*x1`) that uses the lanes for the parallel multiplies and a
second bundle for the reduction.

### Emit Verilog

```bash
./mill -i svarog.runMain svarog.dsp.GenerateVliwDsp --target-dir=target/dsp/
# optional knobs: --lanes=4 --regs=16 --xlen=32
```

## How this would connect to the core (notes, not yet built)

This block is intentionally decoupled from the scalar pipeline so it can't
break existing tests. Two realistic ways to wire it in later:

1. **Memory-mapped coprocessor.** Expose the bundle word and the host
   read/write ports as MMIO registers on the existing bus. Software loads DSP
   registers, writes a bundle to a "go" register, then reads results back. Zero
   changes to fetch/decode — the simplest path, and a good next step.
2. **Custom-opcode trigger.** RISC-V reserves custom opcodes
   (`custom-0 = 0b0001011`, etc.). A scalar instruction can't carry 64 bits, so
   the bundle would still come from memory (e.g. the instruction supplies a
   pointer), with the custom op kicking off one bundle. This keeps DSP code in
   the normal instruction stream but needs decoder + Execute hooks.

A genuine wide-fetch VLIW front end (fetching 64-bit bundles inline) would mean
reworking `Fetch`, the pipeline queues, and hazard handling — out of scope for a
"nothing fancy" prototype, but the building blocks here (encoding, register
file, parallel execute) carry straight over.

## Design choices, briefly

- **4 lanes / 16 regs / 16-bit lanes.** Gives a clean 64-bit bundle and 4-bit
  fields that are easy to read in hex. All three are parameters
  (`DspParams`) if you want to grow them.
- **4-bit op field for 4 ops.** Deliberately oversized so adding MAC, shifts,
  saturating ops, etc. later doesn't change the encoding layout.
- **Single-cycle combinational multiply.** "Nothing fancy" — it shows the
  parallelism. A real DSP would pipeline the multipliers; the register file and
  encoding wouldn't change.
