# 0001: A focused app that reuses existing protocol code

- **Status:** accepted
- **Date:** 2026-10-14

## Context

Existing writer apps for NFC e-paper tags are closed source and tied to one
vendor. They also write unreliably: short timeouts break Samsung and Pixel
phones, and there's no guidance or retry when the phone moves mid-write.

[Magic ePaper](https://github.com/fossasia/magic-epaper-app) (Flutter,
Apache-2.0) implements all three protocol families on Android. It is also a
broad badge and sign maker: online weather, OCR, barcode scanning, bulk CSV.
It requests network and camera access, and its iOS NFC path is not
implemented.

## Decision

Build a focused app: image → panel → write, plus presets. Port the protocol
logic from Magic ePaper under Apache-2.0, keeping its attribution in
[NOTICE](../../NOTICE) once ported code lands.

## Consequences

- A small, auditable codebase with minimal permissions, and multi-vendor
  support without the feature sprawl.
- We maintain our own protocol ports and iOS transport.
- Protocol findings worth sharing are offered upstream.
