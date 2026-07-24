# phrase-pinyin-data

- Repository: https://github.com/mozillazg/phrase-pinyin-data
- Version: 0.19.0
- Revision: `cee0ed6e6e4898580cafd2bd5e3723e20b214aa0`
- `pinyin.txt` SHA-256: `dff030d54e9c9ba48d187fba037d00af410f01c9a867528db6899f539f6e86f7`
- License: MIT, reproduced in `LICENSE`

`local_overrides.txt` contains dictionary-only corrections and additions for
common novel usage. These entries intentionally take precedence over the
pinned upstream file. It does not use a neural G2P model.

`base_lexicon.txt` is the original lexicon from the bundled Piper Chinese
model. It is a build input only; the APK contains the generated merged lexicon.
