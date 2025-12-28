`timescale 1ns / 1ps

module top(
      input clk,
      input rst_n,
      output reg led = 1'b0,
      input uart0_rxd,
      output uart0_txd
      );

      reg [31:0] count = 0;
      wire uart1_txd;

//      reg [3:0] reset_delay = 15;
//      wire cpu_reset = (reset_delay != 0);

//      always @(posedge clk) begin
//          if (!rst_n)
//              reset_delay <= 15;
//          else if (reset_delay != 0)
//              reset_delay <= reset_delay - 1;
//      end

      SvarogSoC soc(
          .clock(clk),
          .reset(!rst_n),
          .io_uarts_0_txd(uart0_txd),
          .io_uarts_0_rxd(uart0_rxd),
          .io_uarts_1_txd(uart1_txd),
          .io_uarts_1_rxd(1'b1)
      );

      always @(posedge clk or negedge rst_n) begin
          if (!rst_n) begin
              led <= 1'b0;
              count <= 0;
          end else begin
              count <= count + 1;
              if (count == 50_000_000) begin
                  led <= !led;
                  count <= 0;
              end
          end
      end
  endmodule
