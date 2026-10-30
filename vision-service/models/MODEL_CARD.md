# Circuit vision model card

## Model summary

- **Runtime files:** `best.onnx`, `legacy-week1.onnx`
- **Task:** Logisim-style circuit component detection followed by deterministic wire/topology recovery
- **Detector family:** YOLO11n
- **Export/runtime format:** ONNX, input shape `1 × 3 × 800 × 800`
- **Classes:** INPUT_PIN, OUTPUT_PIN, AND, OR, NOT, NAND, NOR, XOR, XNOR
- **Primary runtime:** ONNX Runtime on CPU; Ultralytics is not required for normal inference
- **Protocol:** `vision-ir/0.1`

## Provenance

The primary model was trained in run `full-v05-1` with seed `20261028`; early stopping completed at
epoch 32 and selected epoch 7. Training and export used Ultralytics 8.4.158 with a YOLO11n base.
The project-generated training/evaluation domain consists of native Logisim-style, light-background,
orthogonal digital logic diagrams. Training datasets and PyTorch checkpoints are intentionally not
bundled in this source release; reproducible generators and training instructions are under
`vision-service/`.

`legacy-week1.onnx` is a seven-class compatibility model used only by the profile router for the older
dark-green-wire screenshot palette.

## Evaluation scope

The frozen release manifest reports:

- component evaluation: 300 native generated images, 2,769 true positives, 0 false positives,
  0 false negatives, and 300/300 image-level exact matches;
- end-to-end post-fix regression: 300/300 exact topology matches on the frozen native evaluation set;
- scale post-fix regression: 216/216 exact topology runs across 50%, 60%, 80%, 120%, 160% and 200%.

These numbers describe the checked-in generated regression sets. The end-to-end and scale reports mark
themselves `acceptanceEligible: false`; they are regression evidence, not an independently collected,
open-world schematic benchmark. See `vision-service/evaluation/` and
`vision-service/RELEASE_MANIFEST.json` for machine-readable records.

## Intended use

The model is intended for assisted import of shallow combinational circuits rendered in the supported
Logisim visual profile. Results are shown in a review step so a user can correct component types and
pin names before import.

It is not designed as a general PCB, analog schematic, handwritten schematic or safety-critical
recognizer. Dense crossings, unsupported symbols, rotations, unusual themes, heavy compression and
out-of-domain drawing styles may require manual correction or may be rejected by topology validation.

## Integrity

| File | SHA-256 |
| --- | --- |
| `best.onnx` | `1fce32b1b4f5a7f52754264be401d6d6a766eadd75c30305852d5761fa92318b` |
| `legacy-week1.onnx` | `8678fa69e64865655c57d8bc93c9376cbbbe70b34b540ce9719d27071d7e9c26` |

Run `python vision-service/verify_release.py` from the repository root to verify all frozen artifacts.

## Licensing note

The repository is a GPL-3.0 Logisim-evolution derivative. Ultralytics publishes its YOLO software and
models under AGPL-3.0 or a separately obtained enterprise license. Review the upstream terms before
redistributing the weights or using them in a commercial product. ONNX Runtime is MIT-licensed.
See the repository-level `THIRD_PARTY_NOTICES.md` for the dependency boundary.
