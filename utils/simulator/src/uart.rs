/// UART byte decoder using transition-based decoding
///
/// Decodes UART serial transmissions from single-bit TX line.
/// Protocol: 1 start bit (0), 8 data bits (LSB first), 1 stop bit (1)
/// Idle state: TX line is high (1)
///
/// This decoder automatically detects the bit period by measuring transitions
/// and decodes bytes by counting how long the line stays at each level.
pub struct UartDecoder {
    prev_txd: u8,
    bit_samples: Vec<u8>,    // Sampled bit values
    cycles_since_start: u32, // Cycles since start bit detected
    in_byte: bool,           // Track if we're currently receiving a byte
    bit_period: u32,         // Bit period in cycles (~434)
}

impl UartDecoder {
    pub fn new() -> Self {
        Self {
            prev_txd: 1, // Idle is high
            bit_samples: Vec::new(),
            cycles_since_start: 0,
            in_byte: false,
            // UART advances when counter reaches divider value, so each serial bit
            // lasts (divider + 1) core cycles.
            bit_period: 435,
        }
    }

    /// Process one clock cycle of UART TX signal
    /// Returns Some(byte) when a complete byte has been received
    pub fn process(&mut self, txd: u8) -> Option<u8> {
        let txd_bit = txd & 1;

        // Detect start bit (falling edge from 1 to 0)
        if !self.in_byte && self.prev_txd == 1 && txd_bit == 0 {
            self.in_byte = true;
            self.cycles_since_start = 0;
            self.bit_samples.clear();
        }

        // If we're receiving a byte, sample at appropriate times
        if self.in_byte {
            self.cycles_since_start += 1;

            // Sample each data bit in the middle of its period
            // Bit 0 at 1.5 * bit_period, Bit 1 at 2.5 * bit_period, etc.
            for bit_index in 0..8 {
                let sample_time =
                    self.bit_period + (self.bit_period / 2) + (bit_index * self.bit_period);
                if self.cycles_since_start == sample_time
                    && self.bit_samples.len() == bit_index as usize
                {
                    self.bit_samples.push(txd_bit);
                    break;
                }
            }

            // Finalize at the middle of stop bit so we are ready to catch
            // the next falling edge immediately after stop.
            let stop_sample_time = (self.bit_period * 9) + (self.bit_period / 2);
            if self.bit_samples.len() == 8 && self.cycles_since_start >= stop_sample_time {
                let byte = self.decode_bits();
                self.in_byte = false;
                self.bit_samples.clear();
                self.cycles_since_start = 0;
                return Some(byte);
            }
        }

        self.prev_txd = txd_bit;
        None
    }

    fn decode_bits(&self) -> u8 {
        let mut byte = 0u8;
        for (i, &bit) in self.bit_samples.iter().enumerate() {
            if bit == 1 {
                byte |= 1 << i;
            }
        }
        byte
    }
}
