## What this repo is

editors-android contains the native editors of the Euro-Office Android app: the C++ engine, the per-editor Kotlin APIs and the editor UI. It is a submodule of [AndroidEditors](https://github.com/Euro-Office/AndroidEditors), which pins core, sdkjs and the app shell (`documents-app-android`).

## Layout

| Module | Namespace | Purpose |
|---|---|---|
| `engine/` | — | C++ engine (JNI): V8 + sdkjs via core's doctrenderer, tile rendering, x2t I/O |
| `libeditors` | `lib.editors.base` | Kotlin binding to the engine |
| `libdocs`, `libcells`, `libslides` | `lib.editors.{docs,cells,slides}` | Typed editor APIs |
| `libresources` | `lib.editors.resources` | Shared resources |
| `libgeditors` | `lib.editors.gbase` | Editor UI framework, `BaseEditorActivity`, `PdfActivity` |
| `libgdocs`, `libgcells`, `libgslides` | `lib.editors.{gdocs,gcells,gslides}` | Editor UIs: `DocsActivity`, `CellsActivity`, `SlidesActivity` |

The modules are not a standalone Gradle project. `documents-app-android/app_manager/settings.gradle.kts` includes them by path, and they use that project's version catalog (`libs`) and modules (`:libtoolkit`).

## The editor contract

The app launches editors by class name and talks to them through intent extras and result codes defined in `documents-app-android/toolkit/libtoolkit/.../managers/utils/EditorsContract.kt`. Keep these stable:
- Activity class names: `lib.editors.gdocs.ui.activities.DocsActivity`, `lib.editors.gcells.ui.activities.CellsActivity`, `lib.editors.gslides.ui.activities.SlidesActivity`, `lib.editors.gbase.ui.activities.PdfActivity`.
- The namespaces above. The app's ProGuard rules keep these packages.

## Build

From the AndroidEditors checkout:

```sh
cd build
docker buildx bake --allow=fs.read=.. apk
```

## Commits

- Commit messages must follow the Conventional Commits v1.0.0 specification — e.g. feat(engine): render document tiles.
- Every commit containing AI-assisted content must include an `Assisted-by: AGENT_NAME:MODEL_VERSION` trailer.
- Agents must not push, open pull requests or add `Signed-off-by`. See the [AI Contribution Policy](https://github.com/Euro-Office/.github/blob/main/AI_POLICY.md).
