# Changelog

## [0.5.0](https://github.com/cacack/keginker/compare/v0.4.2-alpha.1...v0.5.0) (2026-10-26)


### Features

* add a read-only "Report a display" probe ([336f0d7](https://github.com/cacack/keginker/commit/336f0d7abf7f8b6a3a48d35df451076a8282f906)), closes [#24](https://github.com/cacack/keginker/issues/24)
* **probe:** capture everything a display reports in Report a display ([dd6af06](https://github.com/cacack/keginker/commit/dd6af064d631ad5eb07c553c8d57c0999406b550)), closes [#24](https://github.com/cacack/keginker/issues/24)


### Bug Fixes

* **android:** close the tag on the serialized NFC thread ([f612a06](https://github.com/cacack/keginker/commit/f612a06c18b1140479d588ef21c0fa900417f0bd))
* **android:** disarm a waiting write when "Report a display" opens ([80a3ef4](https://github.com/cacack/keginker/commit/80a3ef40d6dc17e0105cac77c8c243cd4bb158c0)), closes [#45](https://github.com/cacack/keginker/issues/45)
* **android:** keep a failed write's report when opening "Report a display" ([e08ac7a](https://github.com/cacack/keginker/commit/e08ac7a27407655793ace9f6624c31110d71f66f)), closes [#45](https://github.com/cacack/keginker/issues/45)
* **deps:** raise build-tool dependencies past known vulnerabilities ([9a4b5f1](https://github.com/cacack/keginker/commit/9a4b5f1646709061ed388e29662ccbe27eaadc00))
* **diagnostics:** show hidden tag text, cut long replies, say issues are public ([b8b6d27](https://github.com/cacack/keginker/commit/b8b6d27590945c608588414c0d97280ce8bc3abe)), closes [#100](https://github.com/cacack/keginker/issues/100)
* **image:** refuse oversized picked images before decoding ([b77db1f](https://github.com/cacack/keginker/commit/b77db1f060070770cab5668a3514aeb85679347e)), closes [#99](https://github.com/cacack/keginker/issues/99)
* **nfc:** stop writes and display probes that run past a deadline ([cc08e5b](https://github.com/cacack/keginker/commit/cc08e5bb0b6a865ffc038f4bd80642b16784748e))
* **probe:** keep Type 2 reads inside the declared data area ([049580d](https://github.com/cacack/keginker/commit/049580d291460a5ae7990e803b7cf2e17f6ee907)), closes [#45](https://github.com/cacack/keginker/issues/45)
* **probe:** label a vendor query's SELECT as SELECT_AID in the report ([b1ae68e](https://github.com/cacack/keginker/commit/b1ae68e493769c668430d169401fcd04f432e98d)), closes [#24](https://github.com/cacack/keginker/issues/24)
* **probe:** stop Type 2 reads when the capability container isn't NDEF ([918b6a9](https://github.com/cacack/keginker/commit/918b6a9e4aa8506d7fce7b7862215adc2fbd9bde)), closes [#45](https://github.com/cacack/keginker/issues/45)


### Miscellaneous

* release 0.5.0 without a pre-release suffix ([a9a9d5d](https://github.com/cacack/keginker/commit/a9a9d5df1bbd9e3062d7849feb78c7ef17c0ed33))

## [0.4.2-alpha.1](https://github.com/cacack/keginker/compare/v0.4.1-alpha.1...v0.4.2-alpha.1) (2026-10-19)


### Bug Fixes

* **android:** replace the template launcher icon ([bf90181](https://github.com/cacack/keginker/commit/bf9018182b51e12c30c1cd4e693687a3efdf0cd3))
* **ios:** give the app an icon so App Store Connect accepts uploads ([a43e56c](https://github.com/cacack/keginker/commit/a43e56cc1d1ed796b3d7c4ecc38ff0114c80b139)), closes [#27](https://github.com/cacack/keginker/issues/27)

## [0.4.1-alpha.1](https://github.com/cacack/keginker/compare/v0.4.0-alpha.1...v0.4.1-alpha.1) (2026-10-19)


### Bug Fixes

* **ios:** say the NFC sheet is about to write the label ([7b6a63c](https://github.com/cacack/keginker/commit/7b6a63c4e671b0714e9f5bccb7a748521577e596))

## [0.4.0-alpha.1](https://github.com/cacack/keginker/compare/v0.3.0-alpha.1...v0.4.0-alpha.1) (2026-10-19)


### Features

* **android:** offer a diagnostics report after every write, behind a toggle ([8ecb1db](https://github.com/cacack/keginker/commit/8ecb1db6b4ba5a611f66568a2eb54b656ca10e8f))
* **android:** offer a diagnostics report after every write, behind a toggle ([455455d](https://github.com/cacack/keginker/commit/455455d97d4559e7e4fa929d0a3f3912476e80e3)), closes [#80](https://github.com/cacack/keginker/issues/80)
* **ios:** share a diagnostics report after a write ([7b450c3](https://github.com/cacack/keginker/commit/7b450c3d08ae3fcdad24c2091811e53e3071f4aa))
* **ios:** share a diagnostics report after a write ([eb13e18](https://github.com/cacack/keginker/commit/eb13e18e039a67a931472c4a0369d405fff79216)), closes [#50](https://github.com/cacack/keginker/issues/50)


### Bug Fixes

* pack classic 2.7" frames as the view turned 180° ([f226ded](https://github.com/cacack/keginker/commit/f226ded72ab3c51bb40abd14d24d480278be9296))
* pack classic 2.7" frames as the view turned 180° ([40b328f](https://github.com/cacack/keginker/commit/40b328f50d70d66fb6e410a848cbd7b18c9e7b5c)), closes [#8](https://github.com/cacack/keginker/issues/8)
* say when no write was attempted, and don't reopen a stale report ([21feb7f](https://github.com/cacack/keginker/commit/21feb7f77fff25bb178d397464ec6786dafdfa9e)), closes [#80](https://github.com/cacack/keginker/issues/80)

## [0.3.0-alpha.1](https://github.com/cacack/keginker/compare/v0.2.0-alpha.1...v0.3.0-alpha.1) (2026-10-18)


### Features

* **android:** share a diagnostics report after a failed write ([375fa02](https://github.com/cacack/keginker/commit/375fa02ba6b0496de29a0fb8f39a26cbf798dea6))
* **android:** share a diagnostics report after a failed write ([2a6aae6](https://github.com/cacack/keginker/commit/2a6aae694384385d333973229aa8e6a9670b6415)), closes [#49](https://github.com/cacack/keginker/issues/49)


### Bug Fixes

* fail loudly when a write leaves no busy poll in the budget ([6b3999f](https://github.com/cacack/keginker/commit/6b3999f077c1cb37b8f904c12a76a44a43a235c2)), closes [#58](https://github.com/cacack/keginker/issues/58)
* fit each write's worst case in a 15 s time budget ([86601fa](https://github.com/cacack/keginker/commit/86601fac6bd90a7ad8f7fe13df99538c0c61efb1)), closes [#58](https://github.com/cacack/keginker/issues/58)
* **image:** centre the fill crop exactly and weight pixels by area ([19c5b7a](https://github.com/cacack/keginker/commit/19c5b7a26d43e1fbe0d2adeb2d8997c34f7d8f0c)), closes [#60](https://github.com/cacack/keginker/issues/60)
* **image:** keep Floyd-Steinberg rounding from losing error ([b036fb8](https://github.com/cacack/keginker/commit/b036fb805e2e5bd121fbce4591b3267a1d440adb)), closes [#60](https://github.com/cacack/keginker/issues/60)
* keep a failed write's message when a held image is shown ([95b141f](https://github.com/cacack/keginker/commit/95b141f574a41388d5a35bd003494fc5c1069a89)), closes [#59](https://github.com/cacack/keginker/issues/59)
* keep the preview and the written label from disagreeing ([43a9571](https://github.com/cacack/keginker/commit/43a9571951d491d7542744406ec9fa77cd4a2e7d)), closes [#59](https://github.com/cacack/keginker/issues/59)
* say an unconfirmed refresh didn't finish, not that the tag may be unsupported ([9099e0e](https://github.com/cacack/keginker/commit/9099e0e4e5a56f8c30481b3ed20e8bfeb66f3e4a))
* say an unconfirmed refresh didn't finish, not that the tag may be unsupported ([4280fd9](https://github.com/cacack/keginker/commit/4280fd9b26be7cd87d27dee9568147958fe8ea5b)), closes [#54](https://github.com/cacack/keginker/issues/54)
* **waveshare:** fit each write's worst case in a 15 s time budget ([03e7a57](https://github.com/cacack/keginker/commit/03e7a57d04bdfa2efbb41f380503d66eca757290))

## [0.2.0-alpha.1](https://github.com/cacack/keginker/compare/v0.1.0-alpha.1...v0.2.0-alpha.1) (2026-10-18)


### ⚠ BREAKING CHANGES

* the new applicationId and bundle ID make this a different app, so a build installed under the old name must be uninstalled first. Local signing settings are now keginker.* in local.properties (KEGINKER_* in the environment).

### Features

* rename the app to KegInker ([72ae19b](https://github.com/cacack/keginker/commit/72ae19b7d81c57e9e67d9eca280299ece8338bd9))

## 0.1.0-alpha.1 (2026-10-18)


### Features

* write images to Waveshare 2.7in NFC e-paper tags from Android and iOS ([f1fb292](https://github.com/cacack/tagpaper/commit/f1fb292d7141d09a9da219408e1b8eb6082b546c))
