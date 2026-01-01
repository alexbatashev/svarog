set_property -dict {PACKAGE_PIN J19 IOSTANDARD LVCMOS33} [get_ports clk]
create_clock -period 20.000 -name sys_clk_pin -waveform {0.000 5.000} -add [get_ports clk]
set_property -dict {PACKAGE_PIN L18 IOSTANDARD LVCMOS33} [get_ports rst_n]

set_property -dict {PACKAGE_PIN M18 IOSTANDARD LVCMOS33} [get_ports led]

set_property -dict { PACKAGE_PIN  U2  IOSTANDARD LVCMOS33 } [get_ports uart0_rxd]
set_property -dict { PACKAGE_PIN  V2  IOSTANDARD LVCMOS33 } [get_ports uart0_txd]

#set_property -dict {PACKAGE_PIN F13 IOSTANDARD LVCMOS33} [get_ports uart0_rxd]
#set_property -dict {PACKAGE_PIN F14 IOSTANDARD LVCMOS33} [get_ports uart0_txd]
