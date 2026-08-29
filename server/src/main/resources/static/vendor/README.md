# Vendored front-end libraries

Checked in deliberately rather than fetched from a CDN or installed via npm:

* the server must work on a LAN or fully offline (see docker-compose.offline.example.yml),
* it keeps `./gradlew run` as the only build step — no Node toolchain in the loop,
* and it pins exact versions with no third-party runtime dependency.

| File | Version | Source |
|---|---|---|
| `vue.global.prod.js` | Vue 3 | https://unpkg.com/vue@3/dist/vue.global.prod.js |
| `vuetify.min.js` | Vuetify 3.13.2 | https://cdn.jsdelivr.net/npm/vuetify@3/dist/vuetify.min.js |
| `vuetify.min.css` | Vuetify 3.13.2 | https://cdn.jsdelivr.net/npm/vuetify@3/dist/vuetify.min.css |
| `mdi-paths.json` | @mdi/js 7 | extracted subset — SVG path data for the icons actually used |

`mdi-paths.json` exists so the UI needs no Material icon *font*: ~7 KB of path data
instead of a ~1 MB webfont, rendered through a custom Vuetify icon set.

To move to a build step later, drop these and add Vite + `npm i vuetify`; tree-shaking
would cut the JS substantially. Nothing else in the server depends on these paths
apart from `templates/editor.mustache`.
