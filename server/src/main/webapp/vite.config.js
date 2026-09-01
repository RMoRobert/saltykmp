import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

/*
 * The bundle is served by Ktor from the classpath at /static/app, not by Vite, so `base` has to
 * match that path or the chunk imports resolve against "/" and 404.
 *
 * Output goes to server/build/webapp rather than into src/main/resources: generated files under
 * src/ get committed by accident and confuse "what is source". Gradle's processResources copies
 * build/webapp into the jar -- see server/build.gradle.kts.
 *
 * Filenames are fixed rather than content-hashed because the Mustache shell references them by
 * name. That trades long-term cacheability for a shell that never has to be regenerated; the
 * server sends no far-future Cache-Control for /static, so a reload picks changes up anyway.
 */
export default defineConfig({
  plugins: [react()],
  base: "/static/app/",
  build: {
    outDir: "../../../build/webapp",
    emptyOutDir: true,
    rollupOptions: {
      output: {
        entryFileNames: "salty.js",
        chunkFileNames: "salty-[name].js",
        assetFileNames: "salty.[ext]",
      },
    },
  },
  server: {
    // `npm run dev` serves the UI with HMR and hands everything stateful to a running Ktor.
    proxy: {
      "/api": "http://localhost:8080",
      "/login": "http://localhost:8080",
      "/classic": "http://localhost:8080",
    },
  },
});
