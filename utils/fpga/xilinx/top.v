`timescale 1ns / 1ps
//////////////////////////////////////////////////////////////////////////////////
// Company:
// Engineer:
//
// Create Date: 12/27/2025 09:55:20 AM
// Design Name:
// Module Name: top
// Project Name:
// Target Devices:
// Tool Versions:
// Description:
//
// Dependencies:
//
// Revision:
// Revision 0.01 - File Created
// Additional Comments:
//
//////////////////////////////////////////////////////////////////////////////////


module top(
      input clk,
      input rst_n,
      output reg led = 1'b0,
      input uart0_rxd,
      output uart0_txd
      );

      reg [31:0] count = 0;
      wire gpio0_write;
      wire gpio0_output;
      wire gpio1_write;
      wire gpio1_input;
      wire gpio2_write;
      wire gpio2_input;
      wire gpio2_output;
      wire gpio3_write;
      wire gpio3_input;
      wire gpio3_output;

      SvarogSoC soc(
          .clock(clk),
          .reset(!rst_n),
          .io_gpio_0_write(gpio0_write),
          .io_gpio_0_output(gpio0_output),
          .io_gpio_0_input(uart0_rxd),
          .io_gpio_1_write(gpio1_write),
          .io_gpio_1_output(uart0_txd),
          .io_gpio_1_input(gpio1_input),
          .io_gpio_2_write(gpio2_write),
          .io_gpio_2_input(gpio2_input),
          .io_gpio_2_output(gpio2_output),
          .io_gpio_3_write(gpio3_write),
          .io_gpio_3_input(gpio3_input),
          .io_gpio_3_output(gpio3_output),
          .io_rtcClock(clk)
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
