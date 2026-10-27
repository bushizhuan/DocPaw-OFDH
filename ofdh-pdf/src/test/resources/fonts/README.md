The test-only `validation-sans.ttf` is a subset of Noto Sans SC, instantiated at
weight 400 and renamed **OFDH Validation Sans**. It exercises Chinese punctuation,
rare characters and supplementary-plane Unicode without relying on system fonts.
It is not included in the application JAR.

Upstream: https://github.com/google/fonts/tree/main/ofl/notosanssc

Source file: `NotoSansSC[wght].ttf`

Source SHA-256: `a3041811a78c361b1de50f953c805e0244951c21c5bd412f7232ef0d899af0da`

The subset was produced with fontTools 4.58.5: instantiate `wght=400`, retain
the characters in `中文标点：，。温度 36.5 ℃ 生僻字龘𠂇`, remove unused layout
features, and change family/full/PostScript names. The original copyright and
license metadata are retained. See [OFL.txt](OFL.txt) for the SIL Open Font License.
