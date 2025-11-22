# Svarog Documentation

This directory contains documentation for the Svarog processor family.

## Svarog Cores

Svarog is designed as a series of RISC-V cores with different performance and complexity targets.

### Available Cores

- **[Micro](micro/)** - 5-stage in-order pipeline, RV32I
  - Target: Educational, embedded, low-area applications
  - Status: ✅ Implemented

### Planned Cores (Future)

- **Nano** - Minimal area, microcoded
- **Mega** - Superscalar, out-of-order
- **Ultra** - High-performance, advanced features

## Getting Started

For the currently implemented Micro core:

1. [Setup and Build](micro/getting-started.md)
2. [Architecture Overview](micro/architecture.md)
3. [Development Guide](micro/development.md)
4. [Configuration Reference](micro/configuration.md)

## Project Resources

- **Repository**: [github.com/alexbatashev/svarog](https://github.com/alexbatashev/svarog)
- **Chisel**: [chisel-lang.org](https://www.chisel-lang.org/)
- **RISC-V**: [riscv.org](https://riscv.org/)

## Contributing

See the [Development Guide](micro/development.md) for information on:
- Setting up the development environment
- Running tests
- Code style guidelines
- Contribution workflow

## License

See the LICENSE file in the repository root.
