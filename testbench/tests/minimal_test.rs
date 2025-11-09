use testbench::Simulator;

#[test]
fn test_single_addi() {
    let build_dir = format!("{}/verilator_build", env!("CARGO_TARGET_TMPDIR"));
    let simulator = Simulator::new(&build_dir).expect("Failed to create simulator");

    // Initialize and reset FIRST (before loading ROM)
    // ROM uses RegInit so reset clears it to all zeros!
    {
        let mut dut = simulator.model.borrow_mut();
        dut.reset = 1;
        dut.boot_hold = 1;
        dut.regfile_read_en = 0;
        dut.rom_write_en = 0;
        dut.ram_write_en = 0;
        dut.rom_write_mask = 0;
        dut.ram_write_mask = 0;
    }

    // Reset for 5 cycles
    for _ in 0..5 {
        let mut dut = simulator.model.borrow_mut();
        dut.clock = 0;
        dut.eval();
        dut.clock = 1;
        dut.eval();
    }

    // Release reset
    {
        let mut dut = simulator.model.borrow_mut();
        dut.reset = 0;
    }

    // One cycle after reset
    {
        let mut dut = simulator.model.borrow_mut();
        dut.clock = 0;
        dut.eval();
        dut.clock = 1;
        dut.eval();
    }

    // NOW load the instruction AFTER reset
    eprintln!("Loading single ADDI instruction: addi x1, x0, 1 (0x00100093)");

    // Write single instruction to ROM[0] (at address 0x80000000)
    {
        let mut dut = simulator.model.borrow_mut();
        dut.rom_write_en = 1;
        dut.rom_write_addr = 0;
        dut.rom_write_data = 0x00100093;
        dut.rom_write_mask = 0xF;
    }

    // Tick to apply the write
    {
        let mut dut = simulator.model.borrow_mut();
        dut.clock = 0;
        dut.eval();
        dut.clock = 1;
        dut.eval();
    }

    {
        let mut dut = simulator.model.borrow_mut();
        dut.rom_write_en = 0;
    }

    // One cycle
    {
        let mut dut = simulator.model.borrow_mut();
        dut.clock = 0;
        dut.eval();
        dut.clock = 1;
        dut.eval();
    }

    // Release boot hold to start execution
    {
        let mut dut = simulator.model.borrow_mut();
        dut.boot_hold = 0;
    }

    // Run for 50 cycles and print every cycle
    for cycle in 0..50 {
        {
            let mut dut = simulator.model.borrow_mut();
            dut.clock = 0;
            dut.eval();
            dut.clock = 1;
            dut.eval();
        }

        let dut = simulator.model.borrow();
        let pc = dut.debug_pc;
        let reg_write = dut.debug_regWrite;
        let write_addr = dut.debug_writeAddr;
        let write_data = dut.debug_writeData;
        let flush = dut.debug_flush;
        let boot_hold = dut.debug_bootHold;
        let branch_taken = dut.debug_branchTaken;
        let decode_valid = dut.debug_decodeValid;
        let decode_regwrite = dut.debug_decodeRegWrite;
        let instruction = dut.debug_instruction;

        eprintln!(
            "CYCLE {:03}: pc=0x{:08x} inst=0x{:08x} | regWr={} decRegWr={} | flush={} bootHold={} brTaken={} decValid={}",
            cycle, pc, instruction, reg_write, decode_regwrite, flush, boot_hold, branch_taken, decode_valid
        );

        if reg_write != 0 {
            eprintln!("✓ SUCCESS: Register write detected!");
            assert_eq!(write_addr, 1, "Expected write to x1");
            assert_eq!(write_data, 1, "Expected value 1");
            return;
        }
    }

    panic!("✗ FAILED: No register write detected after 50 cycles");
}
