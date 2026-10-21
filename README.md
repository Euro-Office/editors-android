# editors-android

Native document, spreadsheet, presentation and PDF editors for the Euro-Office Android app.

Build and architecture: see [AGENTS.md](AGENTS.md) and [engine/README.md](engine/README.md). The app is built from [AndroidEditors](https://github.com/Euro-Office/AndroidEditors).

## Translations

The editors' texts are translated on [Weblate](https://hosted.weblate.org/projects/euro-office/). The English source is `libresources/src/main/res/values/strings.xml`; Weblate writes the translations to `values-<language>/strings.xml` in the same folder. These texts include:
- the editors' own texts
- the texts sdkjs puts into documents (`editors_sdk_*`)
- the names of the built-in styles (`editors_style_*`)

Settings of the Weblate component:

| Setting | Value |
|---|---|
| File format | Android String Resource |
| File mask | `libresources/src/main/res/values-*/strings.xml` |
| Monolingual base language file | `libresources/src/main/res/values/strings.xml` |
| Template for new translations | `libresources/src/main/res/values/strings.xml` |
| Language code style | Android style (`pt-rBR`) |

Change only the English file by hand; Weblate owns the others.
