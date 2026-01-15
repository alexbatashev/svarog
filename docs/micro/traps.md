# Trap handling design

```
┌─────────────────────────────────────────────────────────────────────┐
│                         Pipeline Stages                             │
│  Execute: illegal inst, ecall, ebreak                               │
│  Memory: misaligned access, page faults                             │
└───────────────────────────┬─────────────────────────────────────────┘
                            │ exceptions (Valid[TrapRequest])
                            ▼
┌─────────────────────────────────────────────────────────────────────┐
│                      TrapController                                 │
│  CSRs: mstatus, mtvec, mepc, mcause, mtval                          │
│                                                                     │
│  • Arbitrates: exceptions (priority) > interrupts                   │
│  • Trap entry: saves state, clears MIE, jumps to mtvec              │
│  • Trap exit (MRET): restores MIE from MPIE, returns to mepc        │
└───────────────────────────┬─────────────────────────────────────────┘
        ▲                   │ io.trap.valid + io.trap.bits.pc
        │                   ▼
        │ pending/cause     Fetch (redirect)
        │
┌───────┴─────────────────────────────────────────────────────────────┐
│                    InterruptController                              │
│  CSRs: mie, mip                                                     │
│                                                                     │
│  Sources: MTIP (timer), MSIP (software), MEIP (external)            │
│  Priority: MEI > MSI > MTI                                          │
└─────────────────────────────────────────────────────────────────────┘
        ▲
        │ mtip, msip, meip
┌───────┴─────────────────────────────────────────────────────────────┐
│  Timer          MSWI             External IRQ                       │
└─────────────────────────────────────────────────────────────────────┘
```
